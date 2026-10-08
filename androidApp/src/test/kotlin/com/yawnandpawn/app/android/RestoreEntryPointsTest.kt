package com.yawnandpawn.app.android

import android.app.AlarmManager
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.android.wake.AlarmSound
import com.yawnandpawn.app.android.wake.AlarmVolume
import com.yawnandpawn.app.android.wake.AndroidAlarmPlayer
import com.yawnandpawn.app.android.wake.WakeActivity
import com.yawnandpawn.app.android.wake.WakeNotifier
import com.yawnandpawn.app.android.wake.WakeService
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.buildActivity
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.data.session.ActiveSessionDao
import com.yawnandpawn.app.data.session.ActiveSessionEntity
import com.yawnandpawn.app.stopApp
import com.yawnandpawn.app.testing.aSession
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowMediaPlayer
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 2.1 (narrowing AD-2 rule 2): `SessionEngine.restore()` runs only from `WakeService`, `MainActivity` and
 * `WakeActivity` creation. App start never restores and never starts a service; a system event arms the session slot.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreEntryPointsTest {
    @get:Rule(order = 0)
    val stopAppRule = StopAppRule()

    private val app = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()

    /** Runs pending broadcasts and launches on the main looper, then waits for the app's background work. */
    private fun awaitWork() {
        shadowOf(Looper.getMainLooper()).idle()
        GlobalContext.get().get<ApplicationScope>().awaitChildren()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun koin() = GlobalContext.get()

    private fun slots(): List<ShadowAlarmManager.ScheduledAlarm> =
        shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.filter {
            shadowOf(it.operation).requestCode == RequestCodes.SESSION_SLOT
        }

    /** The last process committed [state] to runtime.db, then died; a new process starts (the app only, nothing else). */
    private fun killWith(state: SessionState) {
        awaitWork()
        assertEquals(Outcome.Success(Unit), runBlocking { koin().get<ActiveSessionStore>().commit(state) })
        stopApp()
        ShadowLog.clear()
        shadowOf(app).clearStartedServices()
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(3_000, 0) }
        app.onCreate()
        awaitWork()
    }

    @Test
    fun `app start with a stored session restores nothing, starts no service and plays nothing`() {
        val persisted = SessionState.Ringing(aSession())
        killWith(persisted)

        assertEquals(SessionState.Idle, koin().get<SessionEngine>().state.value)
        assertEquals(emptyList(), shadowOf(app).allStartedServices.map { it.action })
        assertNull(koin().get<AndroidAlarmPlayer>().sound)
        assertEquals(Outcome.Success(StoredSession.Found(persisted)), runBlocking { koin().get<ActiveSessionStore>().load() })
    }

    @Test
    fun `MainActivity creation restores the stored session and the wake runtime applies only its entry effects`() {
        // The last process committed a ringing session with a payment in flight, then died.
        val persisted = SessionState.Ringing(aSession().copy(paying = PurchaseIntentId("intent-1")))
        killWith(persisted)

        buildActivity(MainActivity::class.java).create()
        awaitWork()

        val restored = assertIs<SessionState.Ringing>(koin().get<SessionEngine>().state.value)
        assertEquals(persisted.session.sessionId, restored.session.sessionId)
        assertNull(restored.session.paying, "billing is never relaunched")
        assertNotEquals(persisted.session.interactionDeadline, restored.session.interactionDeadline, "a fresh 30-minute deadline")
        val stored = runBlocking { koin().get<ActiveSessionStore>().load() }
        assertEquals(Outcome.Success(StoredSession.Found(restored)), stored, "ProcessRestored is committed")
        // The ringing state's entry effects ran through the wake runtime: sound, service, slot and notification.
        assertEquals(AlarmSound.Default, koin().get<AndroidAlarmPlayer>().sound)
        assertEquals(
            listOf(WakeService.ACTION_RESTORE),
            shadowOf(app).allStartedServices.map { it.action },
            "the runtime starts the service",
        )
        assertEquals(1, slots().size, "the heartbeat slot is armed again")
        assertEquals(restored.session.config.scheduledAt, koin().get<WakeNotifier>().shownFor)
        val effectLogs = ShadowLog.getLogsForTag(AndroidLogger.TAG).map { it.msg }.filter { it.startsWith("SessionEffectLogged") }
        assertTrue(effectLogs.none { "entry=false" in it }, "no one-shot effect ran: $effectLogs")
    }

    @Test
    fun `WakeActivity creation restores the stored session`() {
        val persisted = SessionState.Loud(aSession())
        killWith(persisted)

        buildActivity(WakeActivity::class.java).create()
        awaitWork()

        assertEquals(persisted.session.sessionId, assertIs<SessionState.Loud>(koin().get<SessionEngine>().state.value).session.sessionId)
    }

    @Test
    fun `MainActivity creation finishes a stored Completed session - its history row is written and the engine goes Idle`() {
        // The last process committed Completed, then died before the history row was written.
        val session = aSession().copy(interactionDeadline = null)
        killWith(SessionState.Completed(session))

        buildActivity(MainActivity::class.java).create()
        awaitWork()

        assertEquals(SessionState.Idle, koin().get<SessionEngine>().state.value)
        assertEquals(Outcome.Success(StoredSession.Empty), runBlocking { koin().get<ActiveSessionStore>().load() }, "runtime.db is cleared")
        val row =
            assertIs<Outcome.Success<SessionHistoryRow?>>(
                runBlocking { koin().get<SessionHistoryRepository>().find(session.sessionId) },
            ).value
        assertEquals(SessionOutcome.OnTime, row?.outcome)
    }

    @Test
    fun `a boot with a stored ringing session arms the slot at once, restores nothing and starts no service`() {
        val persisted = SessionState.Grace(aSession())
        killWith(persisted)

        app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(app.packageName))
        awaitWork()

        val slot = slots().single()
        val now = System.currentTimeMillis()
        assertTrue(slot.triggerAtTime in now - 5_000..now + 1_000, "immediate: ${slot.triggerAtTime - now} ms")
        assertEquals(SessionState.Idle, koin().get<SessionEngine>().state.value, "the engine is not touched")
        assertEquals(emptyList(), shadowOf(app).allStartedServices.map { it.action }, "no foreground service from a boot receiver")
        assertNull(koin().get<AndroidAlarmPlayer>().sound)
    }

    @Test
    fun `a boot with a stored snooze arms the slot at its snooze end, and with nothing stored arms nothing`() {
        awaitWork()
        app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(app.packageName))
        awaitWork()
        assertEquals(emptyList(), slots(), "nothing stored")

        val snoozed = SessionState.Snoozed(aSession().copy(interactionDeadline = null, snoozesGranted = 1))
        val snoozeEnd = Deadline(wallMillis = System.currentTimeMillis() + 9 * 60_000, elapsedMillis = 0, bootCount = OTHER_BOOT)
        killWith(SessionState.Snoozed(snoozed.session.copy(snoozeEnd = snoozeEnd)))
        app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(app.packageName))
        awaitWork()

        assertEquals(snoozeEnd.wallMillis, slots().single().triggerAtTime, "a deadline from another boot keeps its wall time")
    }

    @Test
    fun `app start with no session puts back the user alarm volume a crashed session left saved`() {
        awaitWork()
        val audio = app.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        // The last process set the alarm volume for a ring, then died before the session ended.
        AlarmVolume(app, AndroidLogger()).setForRing(100)
        assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), audio.getStreamVolume(AudioManager.STREAM_ALARM))
        stopApp()

        app.onCreate()
        awaitWork()

        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertNull(AlarmVolume(app, AndroidLogger()).saved)
    }

    @Test
    fun `app start with an unreadable session row also puts back the user alarm volume, and leaves the row to a restore`() {
        awaitWork()
        val audio = app.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        AlarmVolume(app, AndroidLogger()).setForRing(100)
        runBlocking { koin().get<ActiveSessionDao>().commit(ActiveSessionEntity("s", "{not json", updatedAt = 0)) }
        stopApp()

        app.onCreate()
        awaitWork()

        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertNull(AlarmVolume(app, AndroidLogger()).saved)
        val stored = assertIs<Outcome.Success<StoredSession>>(runBlocking { koin().get<ActiveSessionStore>().load() }).value
        assertIs<StoredSession.Unreadable>(stored, "only read: nothing restored or cleared")
    }

    @Test
    fun `restore is called only from WakeService, MainActivity and WakeActivity - never from the app or a receiver`() {
        val allowed = setOf("WakeService.kt", "MainActivity.kt", "WakeActivity.kt")
        val sources =
            listOf(File("src/main/kotlin"), File("src/debug/kotlin")).flatMap { root ->
                root.walk().filter { it.extension == "kt" }.toList()
            }
        // Every file that uses the session engine and calls a restore() (the alarm volume's own restore() is in files
        // that never see the engine).
        val callers = sources.filter { "SessionEngine" in it.readText() && RESTORE_CALL.containsMatchIn(it.readText()) }.map { it.name }

        assertEquals(allowed, callers.toSet(), "SessionEngine.restore() callers")
        val receivers = sources.filter { "BroadcastReceiver()" in it.readText() }
        assertTrue(receivers.size >= 3, "the scan sees the receivers: ${receivers.map { it.name }}")
        assertTrue(receivers.none { RESTORE_CALL.containsMatchIn(it.readText()) })
        assertTrue(sources.single { it.name == "YawnAndPawnApp.kt" }.readText().let { !RESTORE_CALL.containsMatchIn(it) })
    }

    private companion object {
        val RESTORE_CALL = Regex("""\.restore\(\)""")

        // A boot count no Robolectric run reports (a real one is small, a missing one is negative).
        const val OTHER_BOOT = 999_999
    }
}

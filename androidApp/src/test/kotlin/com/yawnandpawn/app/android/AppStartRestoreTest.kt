package com.yawnandpawn.app.android

import android.app.AlarmManager
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.android.wake.AlarmSound
import com.yawnandpawn.app.android.wake.AlarmVolume
import com.yawnandpawn.app.android.wake.AndroidAlarmPlayer
import com.yawnandpawn.app.android.wake.WakeNotifier
import com.yawnandpawn.app.android.wake.WakeService
import com.yawnandpawn.app.awaitChildren
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
import com.yawnandpawn.app.stopApp
import com.yawnandpawn.app.testing.aSession
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowMediaPlayer
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AD-2 rule 2: `YawnAndPawnApp.onCreate` takes over the session the last process left in runtime.db. */
@RunWith(RobolectricTestRunner::class)
class AppStartRestoreTest {
    @get:Rule(order = 0)
    val stopAppRule = StopAppRule()

    private val app = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()

    private fun awaitStartUp() = GlobalContext.get().get<ApplicationScope>().awaitChildren()

    @Test
    fun `app start restores a persisted session, dispatches ProcessRestored and the wake runtime applies only its entry effects`() {
        awaitStartUp()
        // The last process committed a ringing session with a payment in flight to runtime.db, then died.
        val persisted = SessionState.Ringing(aSession().copy(paying = PurchaseIntentId("intent-1")))
        val written = runBlocking { GlobalContext.get().get<ActiveSessionStore>().commit(persisted) }
        assertEquals(Outcome.Success(Unit), written)
        stopApp()
        ShadowLog.clear()
        shadowOf(app).clearStartedServices()
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(3_000, 0) }

        app.onCreate()
        awaitStartUp()

        val koin = GlobalContext.get()
        val restored = assertIs<SessionState.Ringing>(koin.get<SessionEngine>().state.value)
        assertEquals(persisted.session.sessionId, restored.session.sessionId)
        assertNull(restored.session.paying, "billing is never relaunched")
        assertNotEquals(persisted.session.interactionDeadline, restored.session.interactionDeadline, "a fresh 30-minute deadline")
        val stored = runBlocking { koin.get<ActiveSessionStore>().load() }
        assertEquals(Outcome.Success(StoredSession.Found(restored)), stored, "ProcessRestored is committed")

        // The ringing state's entry effects ran through the wake runtime: sound, service, slot and notification.
        assertEquals(AlarmSound.Default, koin.get<AndroidAlarmPlayer>().sound)
        val starts = shadowOf(app).allStartedServices.map { it.action }
        assertEquals(listOf(WakeService.ACTION_RESTORE), starts, "the runtime starts the service")
        val slot =
            shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.filter {
                shadowOf(it.operation).requestCode == RequestCodes.SESSION_SLOT
            }
        assertEquals(1, slot.size, "the heartbeat slot is armed again")
        assertEquals(restored.session.config.scheduledAt, koin.get<WakeNotifier>().shownFor)
        val effectLogs = ShadowLog.getLogsForTag(AndroidLogger.TAG).map { it.msg }.filter { it.startsWith("SessionEffectLogged") }
        assertTrue(effectLogs.none { "entry=false" in it }, "no one-shot effect ran: $effectLogs")
    }

    @Test
    fun `app start with no session puts back the user alarm volume a crashed session left saved`() {
        awaitStartUp()
        val audio = app.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        // The last process set the alarm volume for a ring, then died before the session ended.
        AlarmVolume(app, AndroidLogger()).setForRing(100)
        assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), audio.getStreamVolume(AudioManager.STREAM_ALARM))
        stopApp()

        app.onCreate()
        awaitStartUp()

        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertNull(AlarmVolume(app, AndroidLogger()).saved)
    }

    @Test
    fun `app start finishes a persisted Completed session, its history row is written to app db and the engine goes Idle`() {
        awaitStartUp()
        // The last process committed Completed, then died before the history row was written.
        val session = aSession().copy(interactionDeadline = null)
        val written = runBlocking { GlobalContext.get().get<ActiveSessionStore>().commit(SessionState.Completed(session)) }
        assertEquals(Outcome.Success(Unit), written)
        stopApp()

        app.onCreate()
        awaitStartUp()

        val koin = GlobalContext.get()
        assertEquals(SessionState.Idle, koin.get<SessionEngine>().state.value)
        assertEquals(Outcome.Success(StoredSession.Empty), runBlocking { koin.get<ActiveSessionStore>().load() }, "runtime.db is cleared")
        val row =
            assertIs<Outcome.Success<SessionHistoryRow?>>(
                runBlocking { koin.get<SessionHistoryRepository>().find(session.sessionId) },
            ).value
        assertEquals(SessionOutcome.OnTime, row?.outcome)
    }
}

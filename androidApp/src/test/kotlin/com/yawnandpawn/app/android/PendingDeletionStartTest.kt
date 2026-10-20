package com.yawnandpawn.app.android

import android.app.AlarmManager
import android.os.Looper
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.reset.PendingDeletion
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.data.reset.FileDeletionMarker
import com.yawnandpawn.app.stopApp
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 5.9 review (items 2 and 9): the real start-up path with an unfinished "Delete all data" (the marker and a stored
 * alarm left by a crash). A start with no activity (a boot, a time change) finishes it without re-arming the alarm; a
 * session still stored holds it, and nothing is armed meanwhile; MainActivity lands on the empty Home once it is
 * finished (the restoring screen shows meanwhile).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PendingDeletionStartTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()
    private val alarmManager get() = shadowOf(app.getSystemService(AlarmManager::class.java))

    private fun koin() = GlobalContext.get()

    /** Waits for the start-up work launched on the current Koin graph's application scope. */
    private fun awaitStartUp() = koin().get<ApplicationScope>().awaitChildren()

    /** An enabled daily alarm stored by the first app run, and the marker of a deletion that crashed after step 1. */
    private fun leaveAHalfDoneDeletion(): Alarm {
        awaitStartUp()
        val alarm = anAlarm(id = "left", time = LocalTime(7, 0), repeatDays = DayOfWeek.entries.toSet(), requestCode = 1000)
        assertEquals(Outcome.Success(Unit), runBlocking { koin().get<AlarmRepository>().upsert(alarm) })
        FileDeletionMarker.markerFile(app).apply {
            parentFile?.mkdirs()
            writeText("0")
        }
        return alarm
    }

    /** The start-up path again: a new Koin graph and `onCreate`, with no activity. */
    private fun restartApp() {
        stopApp()
        app.onCreate()
    }

    /** Waits (bounded) until the start-up work is done but the resume, which waits for the stored session. */
    private fun awaitAllButTheResume() {
        val job = checkNotNull(koin().get<ApplicationScope>().coroutineContext[Job])
        val deadline = System.currentTimeMillis() + APP_WORK_TIMEOUT_MILLIS
        while (job.children.count { it.isActive } > 1 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MILLIS)
        }
        assertEquals(1, job.children.count { it.isActive }, "only the resume is left waiting")
    }

    private fun awaitMarkerGone() {
        val deadline = System.currentTimeMillis() + APP_WORK_TIMEOUT_MILLIS
        while (FileDeletionMarker.markerFile(app).exists() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MILLIS)
        }
        awaitStartUp()
    }

    @Test
    fun `a start with no activity finishes the deletion, arms nothing and leaves no marker`() {
        leaveAHalfDoneDeletion()

        restartApp()
        awaitMarkerGone()

        assertFalse(FileDeletionMarker.markerFile(app).exists(), "the marker is gone")
        assertEquals(emptyList(), alarmManager.scheduledAlarms, "the half-deleted alarm is never re-armed")
        assertEquals(Outcome.Success(emptyList<Alarm>()), runBlocking { koin().get<AlarmRepository>().listAll() })
        assertFalse(koin().get<PendingDeletion>().pending.value)
    }

    @Test
    fun `with a session still stored the start arms nothing and holds the deletion until the session is gone`() {
        leaveAHalfDoneDeletion()
        assertEquals(Outcome.Success(Unit), runBlocking { koin().get<ActiveSessionStore>().commit(SessionState.Snoozed(aSession())) })

        restartApp()
        awaitAllButTheResume()

        assertTrue(koin().get<PendingDeletion>().pending.value, "held: the stored session is restored by the entry points only")
        assertEquals(emptyList(), alarmManager.scheduledAlarms, "rescheduleAll arms nothing while the deletion is pending")
        assertTrue(FileDeletionMarker.markerFile(app).exists())

        // The session is gone (no ring left to restore): the restore opens the guard and the deletion finishes.
        assertEquals(Outcome.Success(Unit), runBlocking { koin().get<ActiveSessionStore>().clear() })
        runBlocking { koin().get<SessionEngine>().restore() }
        awaitMarkerGone()

        assertFalse(FileDeletionMarker.markerFile(app).exists())
        assertEquals(Outcome.Success(emptyList<Alarm>()), runBlocking { koin().get<AlarmRepository>().listAll() })
        assertEquals(emptyList(), alarmManager.scheduledAlarms)
    }

    @Test
    fun `MainActivity on a half-done deletion lands on the empty Home once it is finished, with nothing armed`() {
        leaveAHalfDoneDeletion()

        restartApp()
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(hasText("No alarms yet.")).fetchSemanticsNodes().isNotEmpty()
            }

            assertFalse(FileDeletionMarker.markerFile(app).exists(), "the marker is gone")
            assertEquals(emptyList(), alarmManager.scheduledAlarms)
            assertEquals(Outcome.Success(emptyList<Alarm>()), runBlocking { koin().get<AlarmRepository>().listAll() })
        }
    }

    private companion object {
        const val POLL_MILLIS = 20L
    }
}

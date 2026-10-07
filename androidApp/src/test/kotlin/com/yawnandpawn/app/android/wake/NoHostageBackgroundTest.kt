package com.yawnandpawn.app.android.wake

import android.app.AlarmManager
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.buildActivity
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.debug.WakeStatus
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 2.11 (FR-SES-9, NFR-13): with the wake screen in the background, a ringing session runs for 5 minutes of
 * heartbeats without ever starting an activity (from the service, the receivers or the runtime), and the alarm keeps
 * playing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class NoHostageBackgroundTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    // Installs the Compose test clock, so the ringing screen's animations never keep the main looper busy.
    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun startedActivities(app: WakeApp): List<Intent> = generateSequence { shadowOf(app.app).nextStartedActivity }.toList()

    /** The session slot's scheduled system alarm, or null when none is armed. */
    private fun slotAlarm(app: WakeApp): ShadowAlarmManager.ScheduledAlarm? =
        shadowOf(app.app.getSystemService(AlarmManager::class.java)).scheduledAlarms.firstOrNull {
            shadowOf(it.operation).requestCode == RequestCodes.SESSION_SLOT
        }

    @Test
    fun `5 minutes of heartbeats with the wake screen stopped start no activity and the alarm keeps playing`() {
        val app = WakeApp()
        val alarm = anAlarm(id = "alarm-a", requestCode = 1000)
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<AlarmRepository>().upsert(alarm) })
        val service = app.ring(AlarmFired(alarm.id, Instant.parse("2027-03-08T06:00:00Z")))
        app.awaitRinging()
        buildActivity(WakeActivity::class.java)
            .setup()
            .pause()
            .stop()
        startedActivities(app)

        repeat(HEARTBEATS) { beat ->
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
            val armed = slotAlarm(app)
            assertNotNull(armed, "the heartbeat slot is armed before minute ${beat + 1}")
            // The heartbeat slot fires through the alarm receiver into the running wake service.
            service.withIntent(WakeService.intent(app.app, WakeService.ACTION_SLOT)).startCommand(0, beat + 2)
            // Handled: SlotFired re-arms the slot one heartbeat on (a new scheduled alarm replaces the fired one).
            app.awaitUntil("heartbeat ${beat + 1} re-arms the slot") { slotAlarm(app).let { it != null && it !== armed } }

            assertEquals(emptyList(), startedActivities(app).map { it.component }, "no activity start at minute ${beat + 1}")
            assertTrue(app.lastMediaPlayer().isReallyPlaying, "the alarm plays at minute ${beat + 1}")
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList(), startedActivities(app).map { it.component }, "no activity start after the heartbeats")
        assertIs<SessionState.Ringing>(app.engine.state.value)
        assertTrue(WakeStatus.isPlaying(), "the debug status reports the alarm playing")
    }

    private companion object {
        const val HEARTBEATS = 5
    }
}

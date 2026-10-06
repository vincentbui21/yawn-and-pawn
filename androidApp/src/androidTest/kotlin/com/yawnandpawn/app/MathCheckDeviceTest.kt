package com.yawnandpawn.app

import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yawnandpawn.app.android.wake.AndroidAlarmPlayer
import com.yawnandpawn.app.android.wake.WakeActivity
import com.yawnandpawn.app.core.alarm.DeleteAlarm
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.CheckRun
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.debug.DebugCheckAnswer
import com.yawnandpawn.app.debug.WakeStatus
import com.yawnandpawn.app.debug.fire.DebugFire
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Story 3.2 on the Gradle Managed Device (ATD API 34): a debug alarm rings and plays, "I'm up" starts the Math check,
 * every problem is solved on the number pad with the answer read through the debug-only [DebugCheckAnswer] hook, the
 * session ends and the alarm sound is released. The synthetic "Debug fire" alarm is deleted afterwards. No notification
 * shade, system app or camera is needed; the wake screen is opened directly.
 *
 * Underscored names: with minSdk 26 the test APK is dexed below DEX 040, which rejects spaces in method names.
 */
@RunWith(AndroidJUnit4::class)
class MathCheckDeviceTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val koin get() = GlobalContext.get()

    private val engine: SessionEngine get() = koin.get()

    private val player: AndroidAlarmPlayer get() = koin.get()

    /** The check run of the session in Grace or Loud; null otherwise. */
    private fun checkRun(): CheckRun? =
        (engine.state.value as? SessionState.Ring)?.takeIf { it !is SessionState.Ringing }?.session?.checkRun

    @Test
    fun a_debug_alarm_stops_once_every_Math_problem_is_solved() {
        val fire = DebugFire(koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get())
        assertTrue("the debug alarm is armed", runBlocking { fire.fire(DebugFire.FireRequest(seconds = 1)) } is Outcome.Success)
        composeRule.waitUntil(RING_TIMEOUT) { engine.state.value is SessionState.Ringing }
        val alarmId = (engine.state.value as SessionState.Ringing).session.config.alarmId
        try {
            poll(RING_TIMEOUT) { WakeStatus.isPlaying() }
            assertTrue("the alarm plays before I'm up", WakeStatus.isPlaying())

            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            ActivityScenario.launch<WakeActivity>(WakeActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
                composeRule.onNodeWithText("I'm up").performClick()
                composeRule.waitUntil(STEP_TIMEOUT) { DebugCheckAnswer.current() != null }
                repeat(MAX_PROBLEMS) {
                    val answer = DebugCheckAnswer.current() ?: return@repeat
                    val before = checkRun()
                    answer.forEach { digit -> composeRule.onNode(hasText(digit.toString()) and hasClickAction()).performClick() }
                    composeRule.onNode(hasText("Check") and hasClickAction()).performClick()
                    // The answer was decided: the next item or entry, a failed attempt, or the session left Grace and Loud.
                    composeRule.waitUntil(STEP_TIMEOUT) {
                        val now = checkRun()
                        now == null || now.step != before?.step || now.failedAttempts != before.failedAttempts
                    }
                    val failed = checkRun()?.failedAttempts ?: 0
                    assertTrue("a right answer is never a failed attempt", failed == (before?.failedAttempts ?: 0))
                }
                poll { engine.state.value !is SessionState.Ring }
            }

            assertFalse("the check is passed: the session no longer rings", engine.state.value is SessionState.Ring)
            poll { !player.isRinging && player.sound == null }
            assertFalse("the ring is over", player.isRinging)
            assertNull("the alarm sound is released", player.sound)
        } finally {
            poll { engine.state.value == SessionState.Idle }
            runBlocking { koin.get<DeleteAlarm>()(alarmId) }
        }
    }

    /** Waits up to [timeoutMillis] for [condition], outside Compose: the wake screen closes when the session ends. */
    private fun poll(
        timeoutMillis: Long = STEP_TIMEOUT,
        condition: () -> Boolean,
    ) {
        repeat((timeoutMillis / POLL_MILLIS).toInt()) {
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val POLL_MILLIS = 100L
        const val RING_TIMEOUT = 60_000L
        const val STEP_TIMEOUT = 10_000L
        const val MAX_PROBLEMS = 10
    }
}

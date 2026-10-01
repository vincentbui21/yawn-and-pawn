package com.yawnandpawn.app.android.wake

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.view.WindowManager
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.session.CheckAnswer
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.ui.format.formatClockTime
import kotlinx.datetime.toLocalDateTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Story 1.14: the wake screen skeleton over the lock screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WakeActivityTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun hiddenFlag(name: String): Int = ActivityInfo::class.java.getField(name).getInt(null)

    /** How the screen writes the fixture's 06:00 UTC alarm in the phone's zone (12-hour clock). */
    private fun alarmTime(app: WakeApp): String =
        formatClockTime(aSessionConfig().scheduledAt.toLocalDateTime(app.koin.get<TimeZoneProvider>().current()).time, is24Hour = false)

    private fun ringing(app: WakeApp) {
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(), listOf(1L), beforeFirstUnlock = false))
        assertIs<SessionState.Ringing>(app.engine.state.value)
    }

    @Test
    fun `the manifest makes it direct-boot aware, over the lock screen, out of Recents, single-task in its own task and not exported`() {
        val app = WakeApp().app
        val info = app.packageManager.getActivityInfo(ComponentName(app, WakeActivity::class.java), 0)

        assertTrue(info.directBootAware, "directBootAware")
        assertFalse(info.exported, "exported")
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
        assertEquals("com.yawnandpawn.app.wake", info.taskAffinity)
        assertTrue(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0, "excludeFromRecents")
        // The two flags are hidden ActivityInfo constants.
        assertTrue(info.flags and hiddenFlag("FLAG_SHOW_WHEN_LOCKED") != 0, "showWhenLocked")
        assertTrue(info.flags and hiddenFlag("FLAG_TURN_SCREEN_ON") != 0, "turnScreenOn")
    }

    @Test
    fun `the manifest declares the wake service as a direct-boot media playback foreground service, not exported`() {
        val app = WakeApp().app
        val info = app.packageManager.getServiceInfo(ComponentName(app, WakeService::class.java), PackageManager.GET_META_DATA)

        assertTrue(info.directBootAware, "directBootAware")
        assertFalse(info.exported, "exported")
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, info.foregroundServiceType)
    }

    @Test
    fun `it shows over the lock screen, turns and keeps the screen on, and Back does nothing`() {
        val app = WakeApp()
        ringing(app)

        val activity = Robolectric.buildActivity(WakeActivity::class.java).setup().get()

        assertTrue(shadowOf(activity).showWhenLocked)
        assertTrue(shadowOf(activity).turnScreenOn)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        activity.onBackPressedDispatcher.onBackPressed()
        assertFalse(activity.isFinishing, "Back does nothing")
    }

    @Test
    @Config(sdk = [26])
    fun `on API 26 it uses the window flags`() {
        val app = WakeApp()
        ringing(app)

        val activity = Robolectric.buildActivity(WakeActivity::class.java).setup().get()

        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        assertEquals(flags, activity.window.attributes.flags and flags)
    }

    @Test
    fun `it shows the alarm time and a 72 dp I'm up that dispatches ImUpTapped`() {
        val app = WakeApp()
        ringing(app)

        ActivityScenario.launch<WakeActivity>(Intent(app.app, WakeActivity::class.java))
        composeRule.onNodeWithText(alarmTime(app)).assertExists()
        val button = composeRule.onNodeWithTag(WakeActivity.IM_UP_TAG)
        assertTrue(button.getBoundsInRoot().let { it.bottom - it.top } >= 72.dp, "the largest wake action")
        button.performClick()
        app.awaitUntil("I'm up is handled") { app.engine.state.value is SessionState.Grace }
    }

    @Test
    fun `any other tap dispatches UserInteracted, which resets the interaction deadline`() {
        val app = WakeApp()
        ringing(app)
        val before = (app.engine.state.value as SessionState.Ringing).session.interactionDeadline
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))

        ActivityScenario.launch<WakeActivity>(Intent(app.app, WakeActivity::class.java))
        composeRule.onNodeWithText(alarmTime(app)).performClick()

        app.awaitUntil("the deadline moves") {
            (app.engine.state.value as? SessionState.Ringing)?.session?.interactionDeadline != before
        }
        assertNotEquals(before, (app.engine.state.value as SessionState.Ringing).session.interactionDeadline)
    }

    @Test
    fun `in the emergency ring I'm up stops it and the screen closes`() {
        val app = WakeApp()
        app.runtime.startEmergency(Instant.parse("2027-03-03T06:00:00Z"), volumePercent = 80, cause = "commit failed")

        val activity = Robolectric.buildActivity(WakeActivity::class.java).setup().get()
        composeRule.onNodeWithTag(WakeActivity.IM_UP_TAG).performClick()
        composeRule.waitForIdle()

        assertNull(app.runtime.emergency.value)
        assertNull(app.player.sound)
        composeRule.waitUntil(timeoutMillis = 5_000) { activity.isFinishing }
    }

    @Test
    fun `opened by the full-screen intent before the session starts it waits, shows the session and closes when it ends`() {
        val app = WakeApp()

        val activity = Robolectric.buildActivity(WakeActivity::class.java).setup().get()
        composeRule.waitForIdle()
        assertFalse(activity.isFinishing, "still Idle: it waits for the session")
        ringing(app)
        composeRule.onNodeWithText(alarmTime(app)).assertExists()

        app.dispatch(SessionEvent.ImUpTapped, SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))
        composeRule.waitUntil(timeoutMillis = 5_000) { activity.isFinishing }
    }
}

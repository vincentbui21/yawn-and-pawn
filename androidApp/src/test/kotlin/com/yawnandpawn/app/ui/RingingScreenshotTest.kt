package com.yawnandpawn.app.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.wake.RingingScreen
import com.yawnandpawn.app.ui.wake.RingingUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Story 1.15 screenshots of the Ringing screen as `WakeActivity` maps it from a session: first ring with and without a
 * label, snooze unavailable, test alarm and the enabled-snooze preview, always Sunrise, at 100% and 200% font scale, on
 * a phone-sized window (12-hour clock: the Robolectric default locale is en-US).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class RingingScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun ringing(
        name: String,
        state: RingingUiState,
    ) = withScreen(PpsThemeMode.Light, content = { RingingScreen(state = state, is24Hour = false, onIntent = {}) }) {
        composeRule.onNodeWithText("I'm up").assertExists()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    @Test
    fun `first ring with a label`() = ringing("wake_ringing_first_sunrise", RingingSamples.firstRing)

    @Test
    @Config(fontScale = 2.0f)
    fun `first ring with a label at 200 percent`() = ringing("wake_ringing_first_sunrise_font200", RingingSamples.firstRing)

    @Test
    fun `first ring without a label`() = ringing("wake_ringing_no_label_sunrise", RingingSamples.firstRingNoLabel)

    @Test
    @Config(fontScale = 2.0f)
    fun `first ring without a label at 200 percent`() = ringing("wake_ringing_no_label_sunrise_font200", RingingSamples.firstRingNoLabel)

    @Test
    fun `snooze unavailable`() = ringing("wake_ringing_snooze_unavailable_sunrise", RingingSamples.snoozeUnavailable)

    @Test
    @Config(fontScale = 2.0f)
    fun `snooze unavailable at 200 percent`() = ringing("wake_ringing_snooze_unavailable_sunrise_font200", RingingSamples.snoozeUnavailable)

    @Test
    fun `test alarm`() = ringing("wake_ringing_test_sunrise", RingingSamples.testAlarm)

    @Test
    @Config(fontScale = 2.0f)
    fun `test alarm at 200 percent`() = ringing("wake_ringing_test_sunrise_font200", RingingSamples.testAlarm)

    // Device test round 1: on a 360 dp phone the 12-hour clock "6:15 AM" stays on one line, AM included.
    @Test
    @Config(qualifiers = "+w360dp")
    fun `first ring on a 360 dp phone`() = ringing("wake_ringing_first_sunrise_w360", RingingSamples.firstRing)

    @Test
    @Config(qualifiers = "+w360dp", fontScale = 2.0f)
    fun `first ring on a 360 dp phone at 200 percent`() = ringing("wake_ringing_first_sunrise_w360_font200", RingingSamples.firstRing)

    @Test
    fun `enabled snooze preview`() = ringing("wake_ringing_snooze_enabled_sunrise", RingingSamples.enabledSnooze)

    @Test
    @Config(fontScale = 2.0f)
    fun `enabled snooze preview at 200 percent`() = ringing("wake_ringing_snooze_enabled_sunrise_font200", RingingSamples.enabledSnooze)

    // Story 2.3: before the first unlock after a reboot, the approved lock-icon snooze control.
    @Test
    fun `before the first unlock`() = ringing("wake_ringing_locked_sunrise", RingingSamples.lockedBeforeUnlock)

    @Test
    @Config(fontScale = 2.0f)
    fun `before the first unlock at 200 percent`() = ringing("wake_ringing_locked_sunrise_font200", RingingSamples.lockedBeforeUnlock)

    // Story 2.4: the same ring after the unlock (from wake_ringing_locked_*): the snooze control changes in place.
    @Test
    fun `after the unlock prices not loaded`() = ringing("wake_ringing_unlocked_prices_sunrise", RingingSamples.afterUnlockPricesNotLoaded)

    @Test
    @Config(fontScale = 2.0f)
    fun `after the unlock prices not loaded at 200 percent`() =
        ringing("wake_ringing_unlocked_prices_sunrise_font200", RingingSamples.afterUnlockPricesNotLoaded)

    @Test
    fun `after the unlock with a snooze on sale`() = ringing("wake_ringing_unlocked_snooze_sunrise", RingingSamples.afterUnlockSnooze)

    @Test
    @Config(fontScale = 2.0f)
    fun `after the unlock with a snooze on sale at 200 percent`() =
        ringing("wake_ringing_unlocked_snooze_sunrise_font200", RingingSamples.afterUnlockSnooze)

    @Test
    fun `paused for a phone call`() = ringing("wake_ringing_phone_call_sunrise", RingingSamples.phoneCall)

    @Test
    @Config(fontScale = 2.0f)
    fun `paused for a phone call at 200 percent`() = ringing("wake_ringing_phone_call_sunrise_font200", RingingSamples.phoneCall)
}

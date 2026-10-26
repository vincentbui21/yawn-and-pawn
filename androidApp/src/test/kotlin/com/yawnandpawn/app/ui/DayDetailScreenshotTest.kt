package com.yawnandpawn.app.ui

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.components.PROGRESS_SKELETON_TAG
import com.yawnandpawn.app.ui.daydetail.DayDetailScreen
import com.yawnandpawn.app.ui.daydetail.DayDetailUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Story 6.7 screenshots of Day detail from stored facts only: an on-time morning, a snoozed morning in two currencies
 * with a payment Google refunded, a missed morning, a fallback before the first unlock, a merged alarm, a test and a
 * skipped session, the alarm-change notes, the skeleton (after 300 ms) and the load failure, each in Light, Dark and
 * Light at 200% font scale (on a taller window, so the whole day shows). The timeline has drawn in (the test clock runs
 * the entrance out) before each capture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class DayDetailScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun capture(
        name: String,
        state: DayDetailUiState,
        mode: PpsThemeMode = PpsThemeMode.Light,
    ) = withScreen(mode, content = { DayDetailScreen(state = state, is24Hour = false, onBack = {}) }) {
        composeRule.waitForIdle()
        if (state.loading) {
            composeRule.mainClock.advanceTimeBy(SKELETON_WAIT_MILLIS)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(PROGRESS_SKELETON_TAG, useUnmergedTree = true).assertExists()
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    private fun all(
        name: String,
        state: DayDetailUiState,
    ) {
        capture("${name}_light", state)
        capture("${name}_dark", state, PpsThemeMode.Dark)
    }

    @Test
    fun `on time light and dark`() = all("day_detail_on_time", DayDetailSamples.onTime)

    @Test
    @Config(qualifiers = "w411dp-h1800dp-mdpi", fontScale = 2.0f)
    fun `on time at 200 percent`() = capture("day_detail_on_time_light_font200", DayDetailSamples.onTime)

    @Test
    fun `snoozed in two currencies with a refunded payment light and dark`() =
        all("day_detail_snoozed_two_currencies_stranded", DayDetailSamples.snoozedTwoCurrenciesStranded)

    @Test
    @Config(qualifiers = "w411dp-h1800dp-mdpi", fontScale = 2.0f)
    fun `snoozed in two currencies with a refunded payment at 200 percent`() =
        capture("day_detail_snoozed_two_currencies_stranded_light_font200", DayDetailSamples.snoozedTwoCurrenciesStranded)

    @Test
    fun `missed light and dark`() = all("day_detail_missed", DayDetailSamples.missed)

    @Test
    @Config(qualifiers = "w411dp-h1800dp-mdpi", fontScale = 2.0f)
    fun `missed at 200 percent`() = capture("day_detail_missed_light_font200", DayDetailSamples.missed)

    @Test
    fun `fallback before the first unlock light and dark`() = all("day_detail_fallback_direct_boot", DayDetailSamples.fallbackDirectBoot)

    @Test
    @Config(qualifiers = "w411dp-h1800dp-mdpi", fontScale = 2.0f)
    fun `fallback before the first unlock at 200 percent`() =
        capture("day_detail_fallback_direct_boot_light_font200", DayDetailSamples.fallbackDirectBoot)

    @Test
    fun `merged alarm light and dark`() = all("day_detail_merged", DayDetailSamples.merged)

    @Test
    @Config(qualifiers = "w411dp-h1800dp-mdpi", fontScale = 2.0f)
    fun `merged alarm at 200 percent`() = capture("day_detail_merged_light_font200", DayDetailSamples.merged)

    @Test
    fun `test light and dark`() = all("day_detail_test", DayDetailSamples.test)

    @Test
    @Config(qualifiers = "w411dp-h1800dp-mdpi", fontScale = 2.0f)
    fun `test at 200 percent`() = capture("day_detail_test_light_font200", DayDetailSamples.test)

    @Test
    fun `skipped light and dark`() = all("day_detail_skipped", DayDetailSamples.skipped)

    @Test
    @Config(qualifiers = "w411dp-h1800dp-mdpi", fontScale = 2.0f)
    fun `skipped at 200 percent`() = capture("day_detail_skipped_light_font200", DayDetailSamples.skipped)

    @Test
    fun `turned off and deleted notes light and dark`() = all("day_detail_turned_off_note", DayDetailSamples.turnedOffNote)

    @Test
    @Config(qualifiers = "w411dp-h1800dp-mdpi", fontScale = 2.0f)
    fun `turned off and deleted notes at 200 percent`() =
        capture("day_detail_turned_off_note_light_font200", DayDetailSamples.turnedOffNote)

    @Test
    fun `the skeleton light and dark`() = all("day_detail_loading", DayDetailSamples.loading)

    @Test
    @Config(qualifiers = "w411dp-h1800dp-mdpi", fontScale = 2.0f)
    fun `the skeleton at 200 percent`() = capture("day_detail_loading_light_font200", DayDetailSamples.loading)

    @Test
    fun `the load failure light and dark`() = all("day_detail_load_failed", DayDetailSamples.loadFailed)

    @Test
    @Config(qualifiers = "w411dp-h1800dp-mdpi", fontScale = 2.0f)
    fun `the load failure at 200 percent`() = capture("day_detail_load_failed_light_font200", DayDetailSamples.loadFailed)

    private companion object {
        /** Past the 300 ms skeleton delay. */
        const val SKELETON_WAIT_MILLIS = 400L
    }
}

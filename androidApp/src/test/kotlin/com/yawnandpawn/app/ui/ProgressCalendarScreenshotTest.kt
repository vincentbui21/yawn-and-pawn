package com.yawnandpawn.app.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.progress.PROGRESS_CALENDAR_TAG
import com.yawnandpawn.app.ui.progress.ProgressScreen
import com.yawnandpawn.app.ui.progress.ProgressUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Story 6.6 screenshots of the Progress calendar card alone: a past month with every outcome (a fallback day and a
 * two-session day among them, which look like any other day of their outcome), this month with today's pill, its chip,
 * and the empty oldest month, each in Light, Dark and Light at 200% font scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1500dp-mdpi")
class ProgressCalendarScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun capture(
        name: String,
        state: ProgressUiState,
        mode: PpsThemeMode = PpsThemeMode.Light,
    ) = withScreen(mode, content = { ProgressScreen(state = state, onIntent = {}) }) {
        composeRule.waitForIdle()
        composeRule
            .onNode(hasTestTag(PROGRESS_CALENDAR_TAG))
            .performScrollTo()
            .captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    private fun all(
        name: String,
        state: ProgressUiState,
    ) {
        capture("${name}_light", state)
        capture("${name}_dark", state, PpsThemeMode.Dark)
    }

    @Test
    fun `every outcome light and dark`() = all("progress_calendar_every_outcome", ProgressSamples.calendarEveryOutcome)

    @Test
    @Config(fontScale = 2.0f)
    fun `every outcome at 200 percent`() = capture("progress_calendar_every_outcome_light_font200", ProgressSamples.calendarEveryOutcome)

    @Test
    fun `today light and dark`() = all("progress_calendar_today", ProgressSamples.typical)

    @Test
    @Config(fontScale = 2.0f)
    fun `today at 200 percent`() = capture("progress_calendar_today_light_font200", ProgressSamples.typical)

    @Test
    fun `a tapped day with its chip light and dark`() = all("progress_calendar_chip", ProgressSamples.calendarChip)

    @Test
    @Config(fontScale = 2.0f)
    fun `a tapped day with its chip at 200 percent`() = capture("progress_calendar_chip_light_font200", ProgressSamples.calendarChip)

    @Test
    fun `the empty oldest month light and dark`() = all("progress_calendar_empty_oldest_month", ProgressSamples.calendarEmptyOldestMonth)

    @Test
    @Config(fontScale = 2.0f)
    fun `the empty oldest month at 200 percent`() =
        capture("progress_calendar_empty_oldest_month_light_font200", ProgressSamples.calendarEmptyOldestMonth)
}

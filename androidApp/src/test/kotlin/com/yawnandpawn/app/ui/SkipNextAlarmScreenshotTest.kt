package com.yawnandpawn.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.editor.AlarmEditorScreen
import com.yawnandpawn.app.ui.editor.CheckChip
import com.yawnandpawn.app.ui.editor.EditorForm
import com.yawnandpawn.app.ui.editor.EditorPane
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.editor.FullEditorSections
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.home.AlarmCard
import com.yawnandpawn.app.ui.home.HomeScreen
import com.yawnandpawn.app.ui.home.HomeUiState
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/** The Story 7.11 states as the app shows them since 0.1.8 (12-hour clock, en-US). */
internal object SkipNextAlarmSamples {
    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    /** The app's editor of a stored 07:00 weekday alarm (0.1.8: no "Offer to skip 2 h before" row any more). */
    val editor =
        EditorUiState(
            isNew = false,
            form = EditorForm(time = LocalTime(7, 0), repeatDays = weekdays),
            ringsIn = Countdown.HoursMinutes(8, 0),
            full =
                FullEditorSections(
                    checks = listOf(CheckChip(CheckType.Math, Difficulty.Easy, 3)),
                    rows = setOf(EditorPane.WakeCheck),
                    types = listOf(CheckType.Math, CheckType.WordUnscramble, CheckType.MemorySequence, CheckType.QrBarcode),
                ),
            hasOverflowMenu = true,
        )

    private val skippedCard = AlarmCard("1", LocalTime(7, 0), weekdays, "Gym", emptyList(), enabled = true, skipped = true)

    /** Home with the 07:00 alarm's next ring skipped: its countdown targets the following ring. */
    val skippedHome =
        HomeUiState(
            nextAlarm = Countdown.DaysHours(1, 8),
            alarms = listOf(skippedCard, AlarmCard("2", LocalTime(8, 30), emptySet(), null, emptyList(), enabled = false)),
        )
}

/**
 * Story 7.11 screenshots: a card whose next ring is skipped (since 0.1.8 by the Skip reminder's "Skip today's alarms"), in
 * Light, Dark and Light at 200 % font scale; the card's "Don't skip" TalkBack action; and the editor without Story 7.11's
 * "Offer to skip 2 h before" row (0.1.8). The skip dialog is gone with it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1400dp-mdpi")
class SkipNextAlarmScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun home(
        name: String,
        state: HomeUiState,
        mode: PpsThemeMode,
        expected: String,
    ) = withScreen(
        mode,
        content = { AppShell(selected = AppTab.Alarms, onSelect = {}) { HomeScreen(state = state, is24Hour = false, onIntent = {}) } },
    ) {
        composeRule.onNodeWithText(expected, useUnmergedTree = true).assertExists()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    @Test
    fun `skipped card in Light`() = home("home_ring_skipped_light", SkipNextAlarmSamples.skippedHome, PpsThemeMode.Light, SKIPPED_CAPTION)

    @Test
    fun `skipped card in Dark`() = home("home_ring_skipped_dark", SkipNextAlarmSamples.skippedHome, PpsThemeMode.Dark, SKIPPED_CAPTION)

    @Test
    @Config(fontScale = 2.0f)
    fun `skipped card in Light at 200 percent`() =
        home("home_ring_skipped_light_font200", SkipNextAlarmSamples.skippedHome, PpsThemeMode.Light, SKIPPED_CAPTION)

    @Test
    fun `a skipped card offers Don't skip to TalkBack, then Duplicate and Delete`() =
        withScreen(
            PpsThemeMode.Light,
            content = { HomeScreen(state = SkipNextAlarmSamples.skippedHome, is24Hour = false, onIntent = {}) },
        ) {
            val labels =
                SemanticsMatcher("has custom actions") { node ->
                    node.config.getOrNull(SemanticsActions.CustomActions)?.map { it.label } == listOf("Don't skip", "Duplicate", "Delete")
                }
            composeRule.onNodeWithText(SKIPPED_CAPTION).assertExists()
            composeRule.onNode(labels).assertExists()
            assertEquals(1, composeRule.onAllNodesWithText(SKIPPED_CAPTION).fetchSemanticsNodes().size)
        }

    @Test
    fun `the editor has no Offer to skip row (0_1_8)`() =
        withScreen(
            PpsThemeMode.Light,
            content = { AlarmEditorScreen(state = SkipNextAlarmSamples.editor, is24Hour = false, onIntent = {}) },
        ) {
            composeRule.onNodeWithText("Vibration", useUnmergedTree = true).assertExists()
            composeRule.onNodeWithText("Offer to skip", substring = true, useUnmergedTree = true).assertDoesNotExist()
        }

    private companion object {
        const val SKIPPED_CAPTION = "Next ring skipped"
    }
}

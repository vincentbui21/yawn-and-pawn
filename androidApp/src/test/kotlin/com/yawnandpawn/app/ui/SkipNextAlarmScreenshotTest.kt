package com.yawnandpawn.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
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
import com.yawnandpawn.app.ui.home.RingsAgain
import com.yawnandpawn.app.ui.home.SkipDialog
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/** The Story 7.11 states as the app shows them (12-hour clock, en-US). */
internal object SkipNextAlarmSamples {
    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    /** The app's editor of a stored 07:00 weekday alarm with "Offer to skip 2 h before" on. */
    val editor =
        EditorUiState(
            isNew = false,
            form = EditorForm(time = LocalTime(7, 0), repeatDays = weekdays, offerSkip = true),
            ringsIn = Countdown.HoursMinutes(8, 0),
            full =
                FullEditorSections(
                    checks = listOf(CheckChip(CheckType.Math, Difficulty.Easy, 3)),
                    rows = setOf(EditorPane.WakeCheck),
                    types = listOf(CheckType.Math, CheckType.WordUnscramble, CheckType.MemorySequence, CheckType.QrBarcode),
                    showOfferSkip = true,
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

    /** "Skip this one" tapped: the dialog over Home, "It rings again tomorrow." under its title (0.1.8). */
    val dialog =
        HomeUiState(
            nextAlarm = Countdown.HoursMinutes(1, 45),
            alarms = listOf(skippedCard.copy(skipped = false)),
            skipDialog = SkipDialog("1", LocalTime(7, 0), ringsAgain = RingsAgain(1, LocalDate(2027, 3, 9))),
        )

    /** After "Skip": the card says so and the snackbar shows. */
    val skippedSnackbar = skippedHome.copy(skippedNoCharge = true)
}

/**
 * Story 7.11 screenshots: the editor's "Offer to skip 2 h before" switch, the skip dialog over Home, and a card whose next
 * ring is skipped (with the snackbar), in Light, Dark and Light at 200 % font scale; and the card's "Don't skip" TalkBack
 * action.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1400dp-mdpi")
class SkipNextAlarmScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun editor(
        name: String,
        mode: PpsThemeMode,
    ) = withScreen(mode, content = { AlarmEditorScreen(state = SkipNextAlarmSamples.editor, is24Hour = false, onIntent = {}) }) {
        composeRule.onNodeWithText(SWITCH, useUnmergedTree = true).assertExists()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    @OptIn(ExperimentalRoborazziApi::class)
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
        val path = "src/test/screenshots/$name.png"
        if (state.skipDialog != null) {
            // The dialog is its own window: capture the whole screen.
            composeRule.onNode(isDialog()).assertExists()
            captureScreenRoboImage(path, roborazziOptions = screenshotOptions)
        } else {
            composeRule.onRoot().captureRoboImage(path, roborazziOptions = screenshotOptions)
        }
    }

    @Test
    fun `Offer to skip switch in Light`() = editor("editor_offer_skip_light", PpsThemeMode.Light)

    @Test
    fun `Offer to skip switch in Dark`() = editor("editor_offer_skip_dark", PpsThemeMode.Dark)

    @Test
    @Config(qualifiers = "+h2400dp", fontScale = 2.0f)
    fun `Offer to skip switch in Light at 200 percent`() = editor("editor_offer_skip_light_font200", PpsThemeMode.Light)

    @Test
    fun `skip dialog in Light`() = home("home_skip_dialog_light", SkipNextAlarmSamples.dialog, PpsThemeMode.Light, DIALOG)

    @Test
    fun `skip dialog in Dark`() = home("home_skip_dialog_dark", SkipNextAlarmSamples.dialog, PpsThemeMode.Dark, DIALOG)

    @Test
    @Config(fontScale = 2.0f)
    fun `skip dialog in Light at 200 percent`() =
        home("home_skip_dialog_light_font200", SkipNextAlarmSamples.dialog, PpsThemeMode.Light, DIALOG)

    @Test
    fun `skipped card in Light`() = home("home_ring_skipped_light", SkipNextAlarmSamples.skippedSnackbar, PpsThemeMode.Light, SKIPPED)

    @Test
    fun `skipped card in Dark`() = home("home_ring_skipped_dark", SkipNextAlarmSamples.skippedSnackbar, PpsThemeMode.Dark, SKIPPED)

    @Test
    @Config(fontScale = 2.0f)
    fun `skipped card in Light at 200 percent`() =
        home("home_ring_skipped_light_font200", SkipNextAlarmSamples.skippedSnackbar, PpsThemeMode.Light, SKIPPED)

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
    fun `the skip dialog's body says when the alarm rings again, by date a week or more away, and a one-time alarm's has none`() {
        val bodies =
            mapOf(
                RingsAgain(1, LocalDate(2027, 3, 9)) to "It rings again tomorrow.",
                RingsAgain(4, LocalDate(2027, 3, 15)) to "It rings again on Monday.",
                RingsAgain(7, LocalDate(2027, 3, 15)) to "It rings again on Monday, March 15.",
                RingsAgain(8, LocalDate(2027, 3, 15)) to "It rings again on Monday, March 15.",
            )
        bodies.forEach { (ringsAgain, body) ->
            val state = SkipNextAlarmSamples.dialog.copy(skipDialog = SkipDialog("1", LocalTime(7, 0), ringsAgain = ringsAgain))
            withScreen(PpsThemeMode.Light, content = { HomeScreen(state = state, is24Hour = false, onIntent = {}) }) {
                composeRule.onNodeWithText(DIALOG).assertExists()
                composeRule.onNodeWithText(body).assertExists()
                composeRule.onNodeWithText("This is logged.", substring = true).assertDoesNotExist()
            }
        }
        val oneTime = SkipNextAlarmSamples.dialog.copy(skipDialog = SkipDialog("1", LocalTime(7, 0)))
        withScreen(PpsThemeMode.Light, content = { HomeScreen(state = oneTime, is24Hour = false, onIntent = {}) }) {
            composeRule.onNodeWithText(DIALOG).assertExists()
            composeRule.onNodeWithText("It rings again", substring = true).assertDoesNotExist()
        }
    }

    private companion object {
        const val SWITCH = "Offer to skip 2 h before"
        const val DIALOG = "Skip the 7:00 AM alarm?"
        const val SKIPPED = "Skipped. No charge."
        const val SKIPPED_CAPTION = "Next ring skipped"
    }
}

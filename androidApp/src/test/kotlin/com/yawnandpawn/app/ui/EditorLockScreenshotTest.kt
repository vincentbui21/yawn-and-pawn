package com.yawnandpawn.app.ui

import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
import com.yawnandpawn.app.ui.editor.LadderPrice
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.home.AlarmCard
import com.yawnandpawn.app.ui.home.DisableUnderLock
import com.yawnandpawn.app.ui.home.HomeScreen
import com.yawnandpawn.app.ui.home.HomeUiState
import com.yawnandpawn.app.ui.settings.WeakeningNote
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

/** The Story 4.6 states as the app shows them (12-hour clock, en-US): the editor under the lock and Home's turn-off dialog. */
internal object EditorLockSamples {
    private fun ladder(vararg prices: String) = prices.map(LadderPrice::Text)

    /** The production editor's rows (Story 3.5: the Wake-up check) for a stored 07:30 alarm. */
    private val full =
        FullEditorSections(
            checks = listOf(CheckChip(CheckType.Math, Difficulty.Medium, 3)),
            rows = setOf(EditorPane.WakeCheck),
            types = listOf(CheckType.Math, CheckType.WordUnscramble, CheckType.MemorySequence, CheckType.QrBarcode),
            feeLadder = ladder("$2.00", "$4.00", "$6.00"),
        )

    private val stored =
        EditorUiState(
            isNew = false,
            form = EditorForm(time = LocalTime(7, 30), repeatDays = DayOfWeek.entries.toSet(), graceSeconds = 20),
            ringsIn = Countdown.HoursMinutes(7, 50),
            full = full,
            hasOverflowMenu = true,
        )

    /** The Snooze sub-screen with Play's prices at base fee $2. */
    val ladderLoaded = stored.copy(pane = EditorPane.Snooze)

    /** Never online: USD from the MoneyFormatter with the approximate note. */
    val ladderApproximate = ladderLoaded.copy(full = full.copy(pricesApproximate = true))

    /** Max snoozes 2: two entries. */
    val ladderMaxTwo = ladderLoaded.copy(full = full.copy(feeLadder = ladder("$2.00", "$4.00")))

    private val note = WeakeningNote(LocalTime(7, 30))

    /** Easier checks (Math at Easy) saved at 23:40 wait for tomorrow's 7:30 ring: the main screen. */
    val pendingMain =
        stored.copy(
            full =
                full.copy(
                    checks = listOf(CheckChip(CheckType.Math, Difficulty.Easy, 3)),
                    checksNote = note,
                    weakeningNote = note,
                ),
        )

    /** The same, in the Wake-up check sub-screen. */
    val pendingChecks = pendingMain.copy(pane = EditorPane.WakeCheck)

    private val home =
        HomeUiState(
            nextAlarm = Countdown.HoursMinutes(7, 59),
            alarms = listOf(AlarmCard("1", LocalTime(7, 30), DayOfWeek.entries.toSet(), "Stand-up", emptyList(), enabled = true)),
        )

    /** 23:31, the 07:30 alarm switched off: "It rings in 7 hours.". */
    val turnOffHours = home.copy(disableDialog = DisableUnderLock("1", LocalTime(7, 30), Countdown.HoursMinutes(7, 59)))

    /** 06:48: "It rings in 42 minutes.". */
    val turnOffMinutes =
        home.copy(
            nextAlarm = Countdown.Minutes(42),
            disableDialog = DisableUnderLock("1", LocalTime(7, 30), Countdown.Minutes(42)),
        )

    /** Back on Home after the editor saved easier checks at 23:40. */
    val savedSnackbar = home.copy(savedUnderLock = note)
}

/**
 * Story 4.6 screenshots: the editor's fee ladder (loaded, approximate, max snoozes 2), the pending-field notes (main
 * screen, Quiet time, Wake-up check), Home's turn-off dialog in hours and minutes, and the saved-under-lock snackbar, in
 * Light, Dark and Light at 200 % font scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1400dp-mdpi")
class EditorLockScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun editor(
        name: String,
        state: EditorUiState,
        mode: PpsThemeMode,
        expected: String,
    ) = withScreen(mode, content = { AlarmEditorScreen(state = state, is24Hour = false, onIntent = {}) }) {
        composeRule.onNodeWithText(expected, useUnmergedTree = true).assertExists()
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
        if (state.disableDialog != null) {
            // The dialog is its own window: capture the whole screen.
            composeRule.onNode(isDialog()).assertExists()
            captureScreenRoboImage(path, roborazziOptions = screenshotOptions)
        } else {
            composeRule.onRoot().captureRoboImage(path, roborazziOptions = screenshotOptions)
        }
    }

    private val ladder = "Snooze 1: $2.00 · 2: $4.00 · 3: $6.00"
    private val approximate = "Approximate. Your local price shows when you're online."
    private val note = "Saved. Takes effect after tomorrow's 7:30 AM alarm."
    private val hours = "It rings in 7 hours."
    private val minutes = "It rings in 42 minutes."

    @Test
    fun `fee ladder in Light`() = editor("editor_fee_ladder_light", EditorLockSamples.ladderLoaded, PpsThemeMode.Light, ladder)

    @Test
    fun `fee ladder in Dark`() = editor("editor_fee_ladder_dark", EditorLockSamples.ladderLoaded, PpsThemeMode.Dark, ladder)

    @Test
    @Config(fontScale = 2.0f)
    fun `fee ladder in Light at 200 percent`() =
        editor("editor_fee_ladder_light_font200", EditorLockSamples.ladderLoaded, PpsThemeMode.Light, ladder)

    @Test
    fun `approximate fee ladder in Light`() =
        editor("editor_fee_ladder_approximate_light", EditorLockSamples.ladderApproximate, PpsThemeMode.Light, approximate)

    @Test
    fun `approximate fee ladder in Dark`() =
        editor("editor_fee_ladder_approximate_dark", EditorLockSamples.ladderApproximate, PpsThemeMode.Dark, approximate)

    @Test
    @Config(fontScale = 2.0f)
    fun `approximate fee ladder in Light at 200 percent`() =
        editor("editor_fee_ladder_approximate_light_font200", EditorLockSamples.ladderApproximate, PpsThemeMode.Light, approximate)

    @Test
    fun `fee ladder with max snoozes 2 in Light`() =
        editor("editor_fee_ladder_max2_light", EditorLockSamples.ladderMaxTwo, PpsThemeMode.Light, "Snooze 1: $2.00 · 2: $4.00")

    @Test
    fun `fee ladder with max snoozes 2 in Dark`() =
        editor("editor_fee_ladder_max2_dark", EditorLockSamples.ladderMaxTwo, PpsThemeMode.Dark, "Snooze 1: $2.00 · 2: $4.00")

    @Test
    @Config(fontScale = 2.0f)
    fun `fee ladder with max snoozes 2 in Light at 200 percent`() =
        editor("editor_fee_ladder_max2_light_font200", EditorLockSamples.ladderMaxTwo, PpsThemeMode.Light, "Snooze 1: $2.00 · 2: $4.00")

    @Test
    fun `pending note on the main screen in Light`() =
        editor("editor_pending_main_light", EditorLockSamples.pendingMain, PpsThemeMode.Light, note)

    @Test
    fun `pending note on the main screen in Dark`() =
        editor("editor_pending_main_dark", EditorLockSamples.pendingMain, PpsThemeMode.Dark, note)

    @Test
    @Config(qualifiers = "+h2400dp", fontScale = 2.0f)
    fun `pending note on the main screen in Light at 200 percent`() =
        editor("editor_pending_main_light_font200", EditorLockSamples.pendingMain, PpsThemeMode.Light, note)

    @Test
    fun `pending checks in Light`() = editor("editor_pending_checks_light", EditorLockSamples.pendingChecks, PpsThemeMode.Light, note)

    @Test
    fun `pending checks in Dark`() = editor("editor_pending_checks_dark", EditorLockSamples.pendingChecks, PpsThemeMode.Dark, note)

    @Test
    @Config(qualifiers = "+h2400dp", fontScale = 2.0f)
    fun `pending checks in Light at 200 percent`() =
        editor("editor_pending_checks_light_font200", EditorLockSamples.pendingChecks, PpsThemeMode.Light, note)

    @Test
    fun `turn-off dialog in hours in Light`() = home("home_turn_off_hours_light", EditorLockSamples.turnOffHours, PpsThemeMode.Light, hours)

    @Test
    fun `turn-off dialog in hours in Dark`() = home("home_turn_off_hours_dark", EditorLockSamples.turnOffHours, PpsThemeMode.Dark, hours)

    @Test
    @Config(fontScale = 2.0f)
    fun `turn-off dialog in hours in Light at 200 percent`() =
        home("home_turn_off_hours_light_font200", EditorLockSamples.turnOffHours, PpsThemeMode.Light, hours)

    @Test
    fun `turn-off dialog in minutes in Light`() =
        home("home_turn_off_minutes_light", EditorLockSamples.turnOffMinutes, PpsThemeMode.Light, minutes)

    @Test
    fun `turn-off dialog in minutes in Dark`() =
        home("home_turn_off_minutes_dark", EditorLockSamples.turnOffMinutes, PpsThemeMode.Dark, minutes)

    @Test
    @Config(fontScale = 2.0f)
    fun `turn-off dialog in minutes in Light at 200 percent`() =
        home("home_turn_off_minutes_light_font200", EditorLockSamples.turnOffMinutes, PpsThemeMode.Light, minutes)

    @Test
    fun `saved-under-lock snackbar in Light`() =
        home("home_saved_under_lock_light", EditorLockSamples.savedSnackbar, PpsThemeMode.Light, note)

    @Test
    fun `saved-under-lock snackbar in Dark`() = home("home_saved_under_lock_dark", EditorLockSamples.savedSnackbar, PpsThemeMode.Dark, note)
}

package com.yawnandpawn.app.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.settings.BuiltSettingsRows
import com.yawnandpawn.app.ui.settings.SettingsPane
import com.yawnandpawn.app.ui.settings.SettingsScreen
import com.yawnandpawn.app.ui.settings.SettingsUiState
import com.yawnandpawn.app.ui.settings.WeakeningNote
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Settings › Snooze states of Story 4.5, as the real app shows them (12-hour clock, en-US). */
internal object SnoozeSettingsSamples {
    /** Prices loaded from Play (its own strings), tier 1, the default 5 snoozes. */
    val loaded =
        SettingsUiState(
            baseFee = "$1.00",
            baseFeeTier = 1,
            feeLadder = listOf("$1.00", "$2.00", "$3.00"),
            maxSnoozes = 5,
        )

    val main = loaded.copy(maxSnoozes = 3)

    val baseFee = loaded.copy(pane = SettingsPane.BaseFee)

    /** Never online: USD amounts from the MoneyFormatter with the approximate note. */
    val approximate = baseFee.copy(pricesApproximate = true)

    /** 23:40, lowered from $3 to $2 before tomorrow's 7:30 alarm. */
    val lowered =
        baseFee.copy(
            baseFee = "$2.00",
            baseFeeTier = 2,
            feeLadder = listOf("$2.00", "$4.00", "$6.00"),
            baseFeeNote = WeakeningNote(LocalTime(7, 30)),
        )

    val maxSnoozesOne = loaded.copy(pane = SettingsPane.MaxSnoozes, maxSnoozes = 1, feeLadder = listOf("$1.00"))

    val maxSnoozesFive = loaded.copy(pane = SettingsPane.MaxSnoozes)
}

/**
 * Story 4.5 screenshots: the Snooze card, Base fee with loaded and approximate prices and the lock note after lowering,
 * and max snoozes at 1 and 5, each in Light, Dark and Light at 200 % font scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class SnoozeSettingsScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun settings(
        name: String,
        state: SettingsUiState,
        mode: PpsThemeMode,
        expected: String,
    ) = withScreen(
        mode,
        content = {
            if (state.pane == SettingsPane.Main) {
                AppShell(selected = AppTab.Settings, onSelect = {}) {
                    SettingsScreen(state = state, is24Hour = false, onIntent = {}, rows = BuiltSettingsRows)
                }
            } else {
                SettingsScreen(state = state, is24Hour = false, onIntent = {}, rows = BuiltSettingsRows)
            }
        },
    ) {
        composeRule.onNodeWithText(expected, useUnmergedTree = true).assertExists()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    private val ladder = "Snooze 1: $1.00 · 2: $2.00 · 3: $3.00"
    private val approximate = "Approximate. Your local price shows when you're online."
    private val lowered = "Saved. Takes effect after tomorrow's 7:30 AM alarm."

    @Test
    fun `Snooze card in Light`() = settings("settings_snooze_light", SnoozeSettingsSamples.main, PpsThemeMode.Light, "Base fee")

    @Test
    fun `Snooze card in Dark`() = settings("settings_snooze_dark", SnoozeSettingsSamples.main, PpsThemeMode.Dark, "Base fee")

    @Test
    @Config(fontScale = 2.0f)
    fun `Snooze card in Light at 200 percent`() =
        settings("settings_snooze_light_font200", SnoozeSettingsSamples.main, PpsThemeMode.Light, "Max snoozes per session")

    @Test
    fun `base fee loaded in Light`() = settings("settings_fee_loaded_light", SnoozeSettingsSamples.baseFee, PpsThemeMode.Light, ladder)

    @Test
    fun `base fee loaded in Dark`() = settings("settings_fee_loaded_dark", SnoozeSettingsSamples.baseFee, PpsThemeMode.Dark, ladder)

    @Test
    @Config(fontScale = 2.0f)
    fun `base fee loaded in Light at 200 percent`() =
        settings("settings_fee_loaded_light_font200", SnoozeSettingsSamples.baseFee, PpsThemeMode.Light, ladder)

    @Test
    fun `base fee approximate in Light`() =
        settings("settings_fee_approximate_light", SnoozeSettingsSamples.approximate, PpsThemeMode.Light, approximate)

    @Test
    fun `base fee approximate in Dark`() =
        settings("settings_fee_approximate_dark", SnoozeSettingsSamples.approximate, PpsThemeMode.Dark, approximate)

    @Test
    @Config(fontScale = 2.0f)
    fun `base fee approximate in Light at 200 percent`() =
        settings("settings_fee_approximate_light_font200", SnoozeSettingsSamples.approximate, PpsThemeMode.Light, approximate)

    @Test
    fun `lock note after lowering in Light`() =
        settings("settings_fee_lowered_light", SnoozeSettingsSamples.lowered, PpsThemeMode.Light, lowered)

    @Test
    fun `lock note after lowering in Dark`() =
        settings("settings_fee_lowered_dark", SnoozeSettingsSamples.lowered, PpsThemeMode.Dark, lowered)

    @Test
    @Config(fontScale = 2.0f)
    fun `lock note after lowering in Light at 200 percent`() =
        settings("settings_fee_lowered_light_font200", SnoozeSettingsSamples.lowered, PpsThemeMode.Light, lowered)

    @Test
    fun `max snoozes 1 in Light`() = settings("settings_max_one_light", SnoozeSettingsSamples.maxSnoozesOne, PpsThemeMode.Light, "1")

    @Test
    fun `max snoozes 1 in Dark`() = settings("settings_max_one_dark", SnoozeSettingsSamples.maxSnoozesOne, PpsThemeMode.Dark, "1")

    @Test
    @Config(fontScale = 2.0f)
    fun `max snoozes 1 in Light at 200 percent`() =
        settings("settings_max_one_light_font200", SnoozeSettingsSamples.maxSnoozesOne, PpsThemeMode.Light, "1")

    @Test
    fun `max snoozes 5 in Light`() = settings("settings_max_five_light", SnoozeSettingsSamples.maxSnoozesFive, PpsThemeMode.Light, "5")

    @Test
    fun `max snoozes 5 in Dark`() = settings("settings_max_five_dark", SnoozeSettingsSamples.maxSnoozesFive, PpsThemeMode.Dark, "5")

    @Test
    @Config(fontScale = 2.0f)
    fun `max snoozes 5 in Light at 200 percent`() =
        settings("settings_max_five_light_font200", SnoozeSettingsSamples.maxSnoozesFive, PpsThemeMode.Light, "5")
}

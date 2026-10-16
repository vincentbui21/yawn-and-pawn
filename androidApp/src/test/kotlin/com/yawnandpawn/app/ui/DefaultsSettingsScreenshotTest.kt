package com.yawnandpawn.app.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.settings.BuiltSettingsRows
import com.yawnandpawn.app.ui.settings.SettingsPane
import com.yawnandpawn.app.ui.settings.SettingsScreen
import com.yawnandpawn.app.ui.settings.SettingsUiState
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Settings states of Story 5.1, as the real app shows them (en-US). */
internal object DefaultsSettingsSamples {
    /** The main screen with the built rows: the stored defaults and the System theme. */
    val main =
        SnoozeSettingsSamples.loaded.copy(
            defaultSnoozeMinutes = 9,
            defaultQuietSeconds = 20,
            vibrateDuringQuietTime = true,
            theme = PpsThemeMode.System,
        )

    /** Chosen 15 min, 30 s, vibration off and Dark. */
    val changed =
        main.copy(defaultSnoozeMinutes = 15, defaultQuietSeconds = 30, vibrateDuringQuietTime = false, theme = PpsThemeMode.Dark)

    val snoozeLength = main.copy(pane = SettingsPane.SnoozeLength, defaultSnoozeMinutes = 10)

    val quietTime = main.copy(pane = SettingsPane.QuietTime, defaultQuietSeconds = 25)
}

/**
 * Story 5.1 screenshots: the Settings main screen with its Snooze, Wake and Appearance cards, and the Default snooze
 * length and Default quiet time sub-screens, each in Light, Dark and Light at 200 % font scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class DefaultsSettingsScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun settings(
        name: String,
        state: SettingsUiState,
        mode: PpsThemeMode,
        expected: String,
        scrollTo: String? = null,
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
        scrollTo?.let { composeRule.onNode(hasText(it) and isSelectable()).performScrollTo() }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    /** The quiet time value is read from the slider's state; its text row is visual only, so the note proves the pane. */
    private val quietNote = "After I'm up, the alarm stays quiet this long while you do your check."

    @Test
    fun `Settings cards in Light`() = settings("settings_defaults_light", DefaultsSettingsSamples.main, PpsThemeMode.Light, "Appearance")

    @Test
    fun `Settings cards in Dark`() = settings("settings_defaults_dark", DefaultsSettingsSamples.main, PpsThemeMode.Dark, "Appearance")

    @Test
    fun `Settings cards changed in Light`() =
        settings("settings_defaults_changed_light", DefaultsSettingsSamples.changed, PpsThemeMode.Light, "15 min")

    @Test
    @Config(fontScale = 2.0f)
    fun `Settings cards in Light at 200 percent`() =
        settings("settings_defaults_light_font200", DefaultsSettingsSamples.main, PpsThemeMode.Light, "Default snooze length")

    @Test
    @Config(fontScale = 2.0f)
    fun `Settings cards scrolled to Appearance in Light at 200 percent`() =
        settings(
            "settings_defaults_appearance_light_font200",
            DefaultsSettingsSamples.main,
            PpsThemeMode.Light,
            "Vibrate during quiet time",
            scrollTo = "Dark",
        )

    @Test
    fun `default snooze length in Light`() =
        settings("settings_snooze_length_light", DefaultsSettingsSamples.snoozeLength, PpsThemeMode.Light, "15 min")

    @Test
    fun `default snooze length in Dark`() =
        settings("settings_snooze_length_dark", DefaultsSettingsSamples.snoozeLength, PpsThemeMode.Dark, "15 min")

    @Test
    @Config(fontScale = 2.0f)
    fun `default snooze length in Light at 200 percent`() =
        settings("settings_snooze_length_light_font200", DefaultsSettingsSamples.snoozeLength, PpsThemeMode.Light, "15 min")

    @Test
    fun `default quiet time in Light`() =
        settings("settings_quiet_time_light", DefaultsSettingsSamples.quietTime, PpsThemeMode.Light, quietNote)

    @Test
    fun `default quiet time in Dark`() =
        settings("settings_quiet_time_dark", DefaultsSettingsSamples.quietTime, PpsThemeMode.Dark, quietNote)

    @Test
    @Config(fontScale = 2.0f)
    fun `default quiet time in Light at 200 percent`() =
        settings("settings_quiet_time_light_font200", DefaultsSettingsSamples.quietTime, PpsThemeMode.Light, quietNote)
}

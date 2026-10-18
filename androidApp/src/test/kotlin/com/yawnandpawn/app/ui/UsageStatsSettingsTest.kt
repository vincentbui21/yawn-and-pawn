package com.yawnandpawn.app.ui

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.telemetry.AnalyticsConsent
import com.yawnandpawn.app.core.telemetry.ConsentStore
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.settings.BuiltSettingsRows
import com.yawnandpawn.app.ui.settings.SettingsScreen
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * Story 5.6: the "Share anonymous usage stats" switch in Settings' untitled general card, off by default, stored at once
 * by a tap (no Save), in the real app; and its screenshots off and on, in Light, Dark and Light at 200 % font scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class UsageStatsSettingsTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val usageStats = hasText("Share anonymous usage stats") and isToggleable()

    private fun storedConsent(): AnalyticsConsent =
        runBlocking {
            GlobalContext
                .get()
                .get<ConsentStore>()
                .observe()
                .first()
        }

    /** The tap's write resumes on the main looper, which this test's paused looper only runs when idled. */
    private fun awaitStored(expected: AnalyticsConsent) {
        runCatching {
            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
                shadowOf(Looper.getMainLooper()).idle()
                storedConsent() == expected
            }
        }
        assertEquals(expected, storedConsent())
    }

    @Test
    fun `the switch is off by default and a tap stores the choice at once, marking the user as asked`() {
        restartKoin(app)
        runBlocking { GlobalContext.get().get<SessionEngine>().restore() }
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithContentDescription("Settings").performClick()
            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(usageStats).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(usageStats).assertIsOff()
            assertEquals(AnalyticsConsent(), storedConsent())

            composeRule.onNode(usageStats).performClick()

            awaitStored(AnalyticsConsent(consent = true, asked = true))
            composeRule.onNode(usageStats).assertIsOn()

            composeRule.onNode(usageStats).performClick()

            awaitStored(AnalyticsConsent(consent = false, asked = true))
            composeRule.onNode(usageStats).assertIsOff()
        }
    }

    private fun screenshot(
        name: String,
        on: Boolean,
        mode: PpsThemeMode,
    ) = withScreen(
        mode,
        content = {
            AppShell(selected = AppTab.Settings, onSelect = {}) {
                SettingsScreen(
                    state = SnoozeSettingsSamples.main.copy(usageStats = on),
                    is24Hour = false,
                    onIntent = {},
                    rows = BuiltSettingsRows,
                )
            }
        },
    ) {
        // At 200 % the general card sits below the Snooze card: scrolled into view, as a user would.
        composeRule.onNode(usageStats).performScrollTo().apply { if (on) assertIsOn() else assertIsOff() }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    @Test
    fun `usage stats off in Light`() = screenshot("settings_usage_stats_off_light", on = false, PpsThemeMode.Light)

    @Test
    fun `usage stats off in Dark`() = screenshot("settings_usage_stats_off_dark", on = false, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `usage stats off in Light at 200 percent`() = screenshot("settings_usage_stats_off_light_font200", on = false, PpsThemeMode.Light)

    @Test
    fun `usage stats on in Light`() = screenshot("settings_usage_stats_on_light", on = true, PpsThemeMode.Light)

    @Test
    fun `usage stats on in Dark`() = screenshot("settings_usage_stats_on_dark", on = true, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `usage stats on in Light at 200 percent`() = screenshot("settings_usage_stats_on_light_font200", on = true, PpsThemeMode.Light)
}

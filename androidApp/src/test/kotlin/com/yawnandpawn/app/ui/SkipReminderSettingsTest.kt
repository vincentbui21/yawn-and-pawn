package com.yawnandpawn.app.ui

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.reminder.SkipReminderStore
import com.yawnandpawn.app.core.session.SessionEngine
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
import kotlin.test.assertTrue

/**
 * 0.1.8: the "Skip reminder" switch in Settings' untitled general card, after "Weekly summary", with the grey subtitle
 * "If you're up early, ask to skip today's alarms.", on for a fresh install and stored at once by a tap (no Save, never
 * a lock note), in the real app; and its screenshots on and off, in Light, Dark and Light at 200 % font scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class SkipReminderSettingsTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val skipReminder = hasText("Skip reminder") and isToggleable()

    private fun storedOn(): Boolean =
        runBlocking {
            GlobalContext
                .get()
                .get<SkipReminderStore>()
                .observe()
                .first()
                .enabled
        }

    /** The tap's write resumes on the main looper, which this test's paused looper only runs when idled. */
    private fun awaitStored(expected: Boolean) {
        runCatching {
            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
                shadowOf(Looper.getMainLooper()).idle()
                storedOn() == expected
            }
        }
        assertEquals(expected, storedOn())
    }

    @Test
    fun `the switch is on for a fresh install, after Weekly summary, and a tap stores it at once with no lock note`() {
        restartKoin(app)
        runBlocking { GlobalContext.get().get<SessionEngine>().restore() }
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithContentDescription("Settings").performClick()
            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(skipReminder).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(skipReminder).performScrollTo().assertIsOn()
            composeRule.onNodeWithText("If you're up early, ask to skip today's alarms.", useUnmergedTree = true).assertExists()
            val weekly = composeRule.onNode(hasText("Weekly summary") and isToggleable()).fetchSemanticsNode().boundsInRoot
            val reminder = composeRule.onNode(skipReminder).fetchSemanticsNode().boundsInRoot
            assertTrue(reminder.top >= weekly.bottom, "Skip reminder sits right after Weekly summary")
            assertTrue(storedOn())

            composeRule.onNode(skipReminder).performClick()

            awaitStored(false)
            composeRule.onNode(skipReminder).assertIsOff()
            composeRule.onNodeWithText("Takes effect", substring = true).assertDoesNotExist()

            composeRule.onNode(skipReminder).performClick()

            awaitStored(true)
            composeRule.onNode(skipReminder).assertIsOn()
            composeRule.onNodeWithText("Takes effect", substring = true).assertDoesNotExist()
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
                    state = SnoozeSettingsSamples.main.copy(skipReminder = on),
                    is24Hour = false,
                    onIntent = {},
                    rows = BuiltSettingsRows,
                )
            }
        },
    ) {
        // The general card sits below the Snooze and Wake cards: scrolled into view, as a user would.
        composeRule.onNode(skipReminder).performScrollTo().apply { if (on) assertIsOn() else assertIsOff() }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    @Test
    fun `skip reminder on in Light`() = screenshot("settings_skip_reminder_on_light", on = true, PpsThemeMode.Light)

    @Test
    fun `skip reminder on in Dark`() = screenshot("settings_skip_reminder_on_dark", on = true, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `skip reminder on in Light at 200 percent`() = screenshot("settings_skip_reminder_on_light_font200", on = true, PpsThemeMode.Light)

    @Test
    fun `skip reminder off in Light`() = screenshot("settings_skip_reminder_off_light", on = false, PpsThemeMode.Light)
}

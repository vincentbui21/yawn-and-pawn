package com.yawnandpawn.app.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.settings.BuiltSettingsRows
import com.yawnandpawn.app.ui.settings.SettingsScreen
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.PpsThemeMode
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
 * 0.1.9: "Rate Yawn & Pawn" and "Share Yawn & Pawn" in Settings, right after "About" in its card, in the real app: Rate
 * opens the Play listing in the Play Store app, Share opens the share sheet with the line about the app and the listing;
 * and their screenshots in Light, Dark and Light at 200 % font scale (scrolled to the end, as a user would).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class RateShareSettingsTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val about = hasText("About") and hasClickAction()
    private val rate = hasText("Rate Yawn & Pawn") and hasClickAction()
    private val share = hasText("Share Yawn & Pawn") and hasClickAction()

    /**
     * Scrolls Settings to its end, as a user would: `performScrollTo` stops as soon as the last row is in the viewport,
     * which reaches under the floating nav bar, so a tap there would land on the bar.
     */
    private fun scrollToEnd() {
        composeRule.onNode(share).performScrollTo()
        repeat(2) { composeRule.onNode(hasScrollAction()).performTouchInput { swipeUp(startY = centerY, endY = top) } }
        composeRule.waitForIdle()
    }

    @Test
    fun `Rate and Share sit right after About, Rate opens the Play listing and Share the share sheet`() {
        restartKoin(app)
        runBlocking { GlobalContext.get().get<SessionEngine>().restore() }
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithContentDescription("Settings").performClick()
            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(share).fetchSemanticsNodes().isNotEmpty()
            }
            scrollToEnd()
            val aboutBounds = composeRule.onNode(about).fetchSemanticsNode().boundsInRoot
            val rateBounds = composeRule.onNode(rate).fetchSemanticsNode().boundsInRoot
            val shareBounds = composeRule.onNode(share).fetchSemanticsNode().boundsInRoot
            assertTrue(rateBounds.top >= aboutBounds.bottom, "Rate sits right after About")
            assertTrue(shareBounds.top >= rateBounds.bottom, "Share sits right after Rate")

            composeRule.onNode(rate).performClick()
            composeRule.waitForIdle()

            val rated = shadowOf(app).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, rated.action)
            assertEquals("market://details?id=com.yawnandpawn.app", rated.dataString)
            assertEquals("com.android.vending", rated.`package`)

            composeRule.onNode(share).performClick()
            composeRule.waitForIdle()

            val chooser = shadowOf(app).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION")
            val shared = checkNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)) { "no shared intent" }
            assertEquals(Intent.ACTION_SEND, shared.action)
            assertEquals("text/plain", shared.type)
            assertEquals(
                "Yawn & Pawn: an alarm clock where snoozing costs money and waking up is free. " +
                    "https://play.google.com/store/apps/details?id=com.yawnandpawn.app",
                shared.getStringExtra(Intent.EXTRA_TEXT),
            )
        }
    }

    private fun screenshot(
        name: String,
        mode: PpsThemeMode,
        noBrowser: Boolean = false,
    ) = withScreen(
        mode,
        content = {
            AppShell(selected = AppTab.Settings, onSelect = {}) {
                SettingsScreen(
                    state = SnoozeSettingsSamples.main.copy(noBrowser = noBrowser),
                    is24Hour = false,
                    onIntent = {},
                    rows = BuiltSettingsRows,
                )
            }
        },
    ) {
        scrollToEnd()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    @Test
    fun `rate and share in Light`() = screenshot("settings_rate_share_light", PpsThemeMode.Light)

    @Test
    fun `rate and share in Dark`() = screenshot("settings_rate_share_dark", PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `rate and share in Light at 200 percent`() = screenshot("settings_rate_share_light_font200", PpsThemeMode.Light)

    @Test
    fun `no browser for Rate in Light`() = screenshot("settings_rate_no_browser_light", PpsThemeMode.Light, noBrowser = true)
}

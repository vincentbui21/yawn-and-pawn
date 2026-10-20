package com.yawnandpawn.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.payments.PaymentsScreen
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.you.BuiltYouRows
import com.yawnandpawn.app.ui.you.YouScreen
import com.yawnandpawn.app.ui.you.YouUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Story 5.8 screenshots as the app shows them: the You tab with its production rows (Money, Privacy and your data,
 * Help with Email support) in the app shell, the same with the no-email-app note, and "How payments & refunds work" at
 * the top and scrolled to its links, in Light and Dark and at 200% font scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class YouScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val you = YouUiState(appVersion = "")

    private fun capture(
        name: String,
        mode: PpsThemeMode,
        scrollToLinks: Boolean = false,
        content: @Composable () -> Unit,
    ) = withScreen(mode, content = content) {
        composeRule.waitForIdle()
        if (scrollToLinks) composeRule.onNode(hasText("Purchase history") and hasClickAction()).performScrollTo()
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    private fun youTab(
        name: String,
        state: YouUiState = you,
        mode: PpsThemeMode = PpsThemeMode.Light,
    ) = capture(name, mode) {
        AppShell(selected = AppTab.You, onSelect = {}) { YouScreen(state = state, onIntent = {}, rows = BuiltYouRows) }
    }

    private fun payments(
        name: String,
        mode: PpsThemeMode = PpsThemeMode.Light,
        scrollToLinks: Boolean = false,
    ) = capture(name, mode, scrollToLinks) { PaymentsScreen(cap = null, onIntent = {}) }

    @Test
    fun `You light`() = youTab("you_light")

    @Test
    fun `You dark`() = youTab("you_dark", mode = PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `You at 200 percent`() = youTab("you_light_font200")

    @Test
    fun `You with no email app light`() = youTab("you_no_email_app_light", you.copy(noEmailAppAddress = SUPPORT_ADDRESS))

    @Test
    fun `You with no email app dark`() = youTab("you_no_email_app_dark", you.copy(noEmailAppAddress = SUPPORT_ADDRESS), PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `You with no email app at 200 percent`() = youTab("you_no_email_app_light_font200", you.copy(noEmailAppAddress = SUPPORT_ADDRESS))

    @Test
    fun `Payments top light`() = payments("payments_top_light")

    @Test
    fun `Payments top dark`() = payments("payments_top_dark", PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `Payments top at 200 percent`() = payments("payments_top_light_font200")

    @Test
    fun `Payments links light`() = payments("payments_links_light", scrollToLinks = true)

    @Test
    fun `Payments links dark`() = payments("payments_links_dark", PpsThemeMode.Dark, scrollToLinks = true)

    @Test
    @Config(fontScale = 2.0f)
    fun `Payments links at 200 percent`() = payments("payments_links_light_font200", scrollToLinks = true)

    private companion object {
        /** A reserved example address, as in the "Problem with a charge?" samples. */
        const val SUPPORT_ADDRESS = "support@example.com"
    }
}

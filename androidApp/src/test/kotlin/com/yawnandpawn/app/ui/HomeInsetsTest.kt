package com.yawnandpawn.app.ui

import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.home.HomeScreen
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.theme.PpsTokens
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/**
 * Device test round 1 (owner screenshots, 360 dp Oppo A96): with a status bar, the Home list starts below the pinned
 * "Yawn & Pawn" header, never under it. Robolectric reports no status bar, so the test applies one (32 dp, like the
 * phone's) to the window. mdpi: one pixel per dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-mdpi")
class HomeInsetsTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val statusBar = 32

    /** Home with the reliability banner as its first item, under a [statusBar] px status bar. */
    private fun homeUnderStatusBar(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    PpsTheme(mode = PpsThemeMode.Light) {
                        AppShell(selected = AppTab.Alarms, onSelect = {}) {
                            HomeScreen(state = HomeSamples.reliability, is24Hour = false, onIntent = {})
                        }
                    }
                }
            }
            composeRule.waitForIdle()
            scenario.onActivity { activity ->
                val insets =
                    WindowInsetsCompat
                        .Builder()
                        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBar, 0, 0))
                        .build()
                ViewCompat.dispatchApplyWindowInsets(activity.findViewById<ViewGroup>(android.R.id.content), insets)
            }
            composeRule.waitForIdle()
            block()
        }
    }

    @Test
    fun `the first item starts below the header, not under the title, with a status bar`() =
        homeUnderStatusBar {
            val title = composeRule.onNodeWithText("Yawn & Pawn").getBoundsInRoot()
            // The header ends one space1 below the title text (its vertical padding); the list then leaves space3.
            val headerBottom = title.bottom + PpsTokens.Spacing.space1
            val banner = composeRule.onNodeWithText("Alarms may not ring. Fix settings").getBoundsInRoot()
            val fix = composeRule.onNodeWithText("Fix").getBoundsInRoot()

            assertTrue(title.top >= statusBar.dp, "the header sits below the status bar (title top ${title.top})")
            assertTrue(banner.top >= headerBottom, "the banner text (top ${banner.top}) is below the header (bottom $headerBottom)")
            // The banner card's top: its tallest child, the 48 dp "Fix" button, sits space2 inside it.
            val cardTop = fix.top - PpsTokens.Spacing.space2
            assertTrue(
                cardTop >= headerBottom + PpsTokens.Spacing.space3 - 1.dp,
                "the first item (top $cardTop) starts space3 below the header (bottom $headerBottom)",
            )
        }

    @Test
    fun `Home at 360 dp with a status bar`() =
        homeUnderStatusBar {
            composeRule.onRoot().captureRoboImage(
                "src/test/screenshots/home_reliability_light_w360_status_bar.png",
                roborazziOptions = screenshotOptions,
            )
        }
}

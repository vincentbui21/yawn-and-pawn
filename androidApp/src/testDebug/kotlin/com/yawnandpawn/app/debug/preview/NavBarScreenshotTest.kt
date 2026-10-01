package com.yawnandpawn.app.debug.preview

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.you.YouScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/**
 * The floating nav bar on the owner's 360 dp phone size (owner decision 2026-10-01, feedback item 26): five slots fit at
 * 100% with every label, and at 200% only the selected tab shows its label; the "+" is at least 56 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-mdpi")
class NavBarScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun show(
        tab: AppTab,
        largeFont: Boolean,
        dark: Boolean = false,
        name: String,
    ) = ActivityScenario.launch(PreviewActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            activity.setContent {
                PreviewFrame(if (dark) PpsThemeMode.Dark else PpsThemeMode.Light, largeFont = largeFont) {
                    AppShell(selected = tab, onSelect = {}) { YouScreen(state = PreviewProgressSamples.you, onIntent = {}) }
                }
            }
        }
        composeRule.waitForIdle()
        val add = composeRule.onNodeWithContentDescription("Add alarm").fetchSemanticsNode()
        val size = with(add.layoutInfo.density) { add.touchBoundsInRoot.width.toDp() }
        assertTrue(size >= 56.dp, "the + is $size")
        composeRule.onRoot().captureRoboImage("src/test/screenshots/preview/navbar_360_$name.png", roborazziOptions = screenshotOptions)
    }

    @Test
    fun `five slots fit at 360 dp`() = show(AppTab.You, largeFont = false, name = "you_light")

    @Test
    fun `five slots fit at 360 dp in Dark`() = show(AppTab.Progress, largeFont = false, dark = true, name = "progress_dark")

    @Test
    fun `at 200 percent only the selected label shows, uncut`() = show(AppTab.Progress, largeFont = true, name = "progress_font200")

    @Test
    fun `at 200 percent the Settings label fits too`() = show(AppTab.Settings, largeFont = true, name = "settings_font200")
}

package com.yawnandpawn.app.debug

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the debug-only theme showcase in every token set and at 200% font scale.
 * In src/testDebug because the showcase exists only in the debug variant.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1500dp-mdpi")
class ThemeShowcaseScreenshotTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun capture(
        name: String,
        mode: PpsThemeMode = PpsThemeMode.System,
        wake: Boolean = false,
    ) = withShowcase(mode, wake) {
        composeRule.onNodeWithText("outcome-marker").assertExists()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    @Test
    fun `the showcase in Light matches the baseline`() {
        capture("theme_showcase_light", mode = PpsThemeMode.Light)
    }

    @Test
    fun `the showcase in Dark matches the baseline`() {
        capture("theme_showcase_dark", mode = PpsThemeMode.Dark)
    }

    @Test
    fun `the showcase in Sunrise matches the baseline`() {
        capture("theme_showcase_sunrise", wake = true)
    }

    @Test
    @Config(qualifiers = "+h2400dp", fontScale = 2.0f)
    fun `the showcase in Light at 200 percent font scale matches the baseline`() {
        capture("theme_showcase_light_font200", mode = PpsThemeMode.Light)
    }
}

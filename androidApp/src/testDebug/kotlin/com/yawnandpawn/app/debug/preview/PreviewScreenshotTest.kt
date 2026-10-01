package com.yawnandpawn.app.debug.preview

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.yawnandpawn.app.screenshotOptions
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalRoborazziApi::class)
private fun capture(
    composeRule: androidx.compose.ui.test.junit4.ComposeTestRule,
    case: PreviewCase,
) = withPreview(case) {
    composeRule.waitForIdle()
    val path = "src/test/screenshots/preview/${case.name}.png"
    // A dialog is its own window: capture the whole screen so it is in the picture.
    if (case.item.hasDialog) {
        captureScreenRoboImage(path, roborazziOptions = screenshotOptions)
    } else {
        composeRule.onRoot().captureRoboImage(path, roborazziOptions = screenshotOptions)
    }
}

/**
 * Design preview round 1: a screenshot of every screen state in the preview menu, on a phone-sized window (411 x 891 dp,
 * so the wake thumb zone is where it is on a phone). App screens in Light and Dark, wake screens in Sunrise, primary
 * states also at 200% font scale.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class PreviewScreenshotTest(
    private val case: PreviewCase,
) {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `the preview state matches its baseline`() = capture(composeRule, case)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = PreviewCase.all(tall = false).map { arrayOf(it) }
    }
}

/** The full-editor states: a window tall enough for the whole scrolling form (also at 200%), so clipping shows. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h2400dp-mdpi")
class PreviewEditorScreenshotTest(
    private val case: PreviewCase,
) {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `the editor state matches its baseline`() = capture(composeRule, case)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = PreviewCase.all(tall = true).filter { !it.largeFont }.map { arrayOf(it) }
    }
}

/** The primary full-editor state at 200% font scale needs an even taller window. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h4400dp-mdpi")
class PreviewEditorLargeFontScreenshotTest(
    private val case: PreviewCase,
) {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `the editor state at 200 percent matches its baseline`() = capture(composeRule, case)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = PreviewCase.all(tall = true).filter { it.largeFont }.map { arrayOf(it) }
    }
}

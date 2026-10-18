package com.yawnandpawn.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.reliability.DeviceMaker
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.reliability.ManufacturerGuidanceScreen
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Story 5.5 screenshots of the maker guidance as the app shows it, for each of the six covered makers, in Light and Dark
 * and at 200% font scale (owner design review: filled "Open settings", outlined "I've done this").
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class ManufacturerGuidanceScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun capture(
        name: String,
        maker: DeviceMaker,
        mode: PpsThemeMode = PpsThemeMode.Light,
    ) = withScreen(mode, content = { Guidance(maker) }) {
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    @Composable
    private fun Guidance(maker: DeviceMaker) = ManufacturerGuidanceScreen(maker = maker, onBack = {}, onOpenSettings = {}, onDone = {})

    @Test
    fun `TalkBack reads each step as one item with its number, and both buttons are buttons`() =
        withScreen(PpsThemeMode.Light, content = { Guidance(DeviceMaker.Xiaomi) }) {
            composeRule.onNode(hasText("1") and hasText("Turn on Autostart for Yawn & Pawn.")).assertExists()
            composeRule.onNode(hasText("3") and hasText("Lock the app in Recents so it stays open.")).assertExists()
            composeRule.onNode(hasText("Open settings") and hasClickAction()).assertExists()
            composeRule.onNode(hasText("I've done this") and hasClickAction()).assertExists()
        }

    @Test
    fun `Xiaomi light`() = capture("manufacturer_guidance_xiaomi_light", DeviceMaker.Xiaomi)

    @Test
    fun `Xiaomi dark`() = capture("manufacturer_guidance_xiaomi_dark", DeviceMaker.Xiaomi, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `Xiaomi at 200 percent`() = capture("manufacturer_guidance_xiaomi_light_font200", DeviceMaker.Xiaomi)

    @Test
    fun `Samsung light`() = capture("manufacturer_guidance_samsung_light", DeviceMaker.Samsung)

    @Test
    fun `Samsung dark`() = capture("manufacturer_guidance_samsung_dark", DeviceMaker.Samsung, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `Samsung at 200 percent`() = capture("manufacturer_guidance_samsung_light_font200", DeviceMaker.Samsung)

    @Test
    fun `Huawei light`() = capture("manufacturer_guidance_huawei_light", DeviceMaker.Huawei)

    @Test
    fun `Huawei dark`() = capture("manufacturer_guidance_huawei_dark", DeviceMaker.Huawei, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `Huawei at 200 percent`() = capture("manufacturer_guidance_huawei_light_font200", DeviceMaker.Huawei)

    @Test
    fun `Oppo and Realme light`() = capture("manufacturer_guidance_oppo_realme_light", DeviceMaker.OppoRealme)

    @Test
    fun `Oppo and Realme dark`() = capture("manufacturer_guidance_oppo_realme_dark", DeviceMaker.OppoRealme, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `Oppo and Realme at 200 percent`() = capture("manufacturer_guidance_oppo_realme_light_font200", DeviceMaker.OppoRealme)

    @Test
    fun `Vivo light`() = capture("manufacturer_guidance_vivo_light", DeviceMaker.Vivo)

    @Test
    fun `Vivo dark`() = capture("manufacturer_guidance_vivo_dark", DeviceMaker.Vivo, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `Vivo at 200 percent`() = capture("manufacturer_guidance_vivo_light_font200", DeviceMaker.Vivo)

    @Test
    fun `OnePlus light`() = capture("manufacturer_guidance_oneplus_light", DeviceMaker.OnePlus)

    @Test
    fun `OnePlus dark`() = capture("manufacturer_guidance_oneplus_dark", DeviceMaker.OnePlus, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `OnePlus at 200 percent`() = capture("manufacturer_guidance_oneplus_light_font200", DeviceMaker.OnePlus)
}

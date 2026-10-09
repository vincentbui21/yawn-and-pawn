package com.yawnandpawn.app.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.wake.CheckScreen
import com.yawnandpawn.app.ui.wake.RingingScreen
import com.yawnandpawn.app.ui.wake.SnoozeSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/**
 * Story 4.13 screenshots of the snooze confirm sheet ([SnoozeSheetSamples]): confirm (with and without the tax note),
 * the last snooze, unlocking and already paid, over Ringing and over a check, in Sunrise at 100% and 200% font scale.
 * Every sheet button is at least 64 dp and fully on screen without scrolling, at 200% too.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class SnoozeSheetScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun buttons(sheet: SnoozeSheet): List<String> =
        when (sheet) {
            is SnoozeSheet.Confirm -> listOf("Pay $${sheet.price.micros / MICROS}.00 and snooze", "I'll get up")
            is SnoozeSheet.Unlocking -> listOf("Cancel")
            is SnoozeSheet.AlreadyPaid -> listOf("Use it", "Not now")
        }

    /**
     * Every button (and a tax note) is whole inside the window without scrolling (review fixes 1 and 3), and each button
     * is at least 64 dp tall.
     */
    private fun assertButtons(sheet: SnoozeSheet) {
        val window = composeRule.onRoot().getBoundsInRoot()
        val whole = { label: String ->
            val node = composeRule.onNodeWithText(label).assertIsDisplayed()
            val bounds = node.getBoundsInRoot()
            assertTrue(bounds.top >= window.top && bounds.bottom <= window.bottom, "$label is whole on screen: $bounds in $window")
            node
        }
        buttons(sheet).forEach { label -> whole(label).assertHeightIsAtLeast(64.dp) }
        if (sheet is SnoozeSheet.Confirm && sheet.showTaxNote) whole(TAX_NOTE)
    }

    private fun shoot(
        name: String,
        sheet: SnoozeSheet,
        font200: Boolean,
    ) {
        val suffix = if (font200) "_font200" else ""
        withScreen(
            PpsThemeMode.Light,
            content = { RingingScreen(SnoozeSheetSamples.overRinging(sheet), is24Hour = false, onIntent = {}) },
        ) {
            assertButtons(sheet)
            composeRule.onRoot().captureRoboImage(
                "src/test/screenshots/wake_sheet_${name}_ringing_sunrise$suffix.png",
                roborazziOptions = screenshotOptions,
            )
        }
        withScreen(PpsThemeMode.Light, content = { CheckScreen(SnoozeSheetSamples.overCheck(sheet), onIntent = {}) }) {
            assertButtons(sheet)
            composeRule.onRoot().captureRoboImage(
                "src/test/screenshots/wake_sheet_${name}_check_sunrise$suffix.png",
                roborazziOptions = screenshotOptions,
            )
        }
    }

    @Test
    fun confirm() = shoot("confirm", SnoozeSheetSamples.confirm, font200 = false)

    @Test
    @Config(fontScale = 2.0f)
    fun `confirm at 200 percent`() = shoot("confirm", SnoozeSheetSamples.confirm, font200 = true)

    @Test
    fun `confirm with the tax note`() = shoot("confirm_tax", SnoozeSheetSamples.confirmTax, font200 = false)

    @Test
    @Config(fontScale = 2.0f)
    fun `confirm with the tax note at 200 percent`() = shoot("confirm_tax", SnoozeSheetSamples.confirmTax, font200 = true)

    @Test
    fun `last snooze`() = shoot("last", SnoozeSheetSamples.lastSnooze, font200 = false)

    @Test
    @Config(fontScale = 2.0f)
    fun `last snooze at 200 percent`() = shoot("last", SnoozeSheetSamples.lastSnooze, font200 = true)

    @Test
    fun unlocking() = shoot("unlocking", SnoozeSheetSamples.unlocking, font200 = false)

    @Test
    @Config(fontScale = 2.0f)
    fun `unlocking at 200 percent`() = shoot("unlocking", SnoozeSheetSamples.unlocking, font200 = true)

    @Test
    fun `already paid`() = shoot("already_paid", SnoozeSheetSamples.alreadyPaid, font200 = false)

    @Test
    @Config(fontScale = 2.0f)
    fun `already paid at 200 percent`() = shoot("already_paid", SnoozeSheetSamples.alreadyPaid, font200 = true)

    private companion object {
        const val MICROS = 1_000_000
        const val TAX_NOTE = "Google Play shows the final total, including any tax."
    }
}

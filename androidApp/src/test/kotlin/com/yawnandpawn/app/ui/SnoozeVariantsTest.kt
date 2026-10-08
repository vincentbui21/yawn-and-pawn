package com.yawnandpawn.app.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.wake.CheckScreen
import com.yawnandpawn.app.ui.wake.RingingScreen
import com.yawnandpawn.app.ui.wake.SNOOZE_BLOCK_ICON_TAG
import com.yawnandpawn.app.ui.wake.SNOOZE_LOCK_ICON_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * Story 4.7: every `button-snooze` variant from the production policy ([SnoozeVariantSamples]) on the Ringing screen and
 * on the Check screen's footer, always Sunrise: the exact copy (EXPERIENCE.md) and TalkBack's "Snooze unavailable,
 * {reason}" for each disabled variant, "I'm up" enabled and visible in every variant (FR-RNG-9), and a screenshot of each
 * at 100% and 200% font scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class SnoozeVariantsTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    /** The variant's snooze control: the enabled button by its label, a disabled one by what TalkBack reads. */
    private fun assertSnooze(variant: SnoozeVariant) {
        val talkBack = variant.talkBack
        if (talkBack == null) {
            composeRule
                .onNodeWithText(variant.label)
                .assertIsDisplayed()
                .assertIsEnabled()
                .assertHasClickAction()
        } else {
            composeRule.onNodeWithContentDescription(talkBack).assertExists().assertIsNotEnabled()
            // The visible label (TalkBack reads the row's description; the label lives in the unmerged tree).
            composeRule.onNodeWithText(variant.label, useUnmergedTree = true).assertIsDisplayed()
            val icon = if (variant == SnoozeVariant.BeforeFirstUnlock) SNOOZE_LOCK_ICON_TAG else SNOOZE_BLOCK_ICON_TAG
            composeRule.onNodeWithTag(icon, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    private fun ringing(
        variant: SnoozeVariant,
        shot: Boolean = true,
        suffix: String = "",
    ) = withScreen(
        PpsThemeMode.Light,
        content = { RingingScreen(state = SnoozeVariantSamples.ringing(variant), is24Hour = false, onIntent = {}) },
    ) {
        composeRule
            .onNodeWithText("I'm up")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
        assertSnooze(variant)
        if (shot) {
            composeRule.onRoot().captureRoboImage(
                "src/test/screenshots/wake_ringing_snooze_${variant.shot}_sunrise$suffix.png",
                roborazziOptions = screenshotOptions,
            )
        }
    }

    private fun check(
        variant: SnoozeVariant,
        shot: Boolean = true,
        suffix: String = "",
    ) = withScreen(PpsThemeMode.Light, content = { CheckScreen(state = SnoozeVariantSamples.check(variant), onIntent = {}) }) {
        composeRule.onNodeWithText("Problem 1 of 3").assertExists()
        assertSnooze(variant)
        if (shot) {
            composeRule.onRoot().captureRoboImage(
                "src/test/screenshots/wake_check_snooze_${variant.shot}_sunrise$suffix.png",
                roborazziOptions = screenshotOptions,
            )
        }
    }

    @Test
    fun `the policy gives each variant its own reason`() {
        val shown = SnoozeVariant.entries.map { SnoozeVariantSamples.ringing(it).snooze }
        assertEquals(SnoozeVariant.entries.size, shown.toSet().size, "$shown")
    }

    @Test
    fun `ringing available`() = ringing(SnoozeVariant.Available)

    @Test
    @Config(fontScale = 2.0f)
    fun `ringing available at 200 percent`() = ringing(SnoozeVariant.Available, suffix = "_font200")

    @Test
    fun `ringing test mode`() = ringing(SnoozeVariant.TestMode, shot = false)

    @Test
    fun `ringing before the first unlock`() = ringing(SnoozeVariant.BeforeFirstUnlock, shot = false)

    @Test
    fun `ringing max snoozes`() = ringing(SnoozeVariant.MaxSnoozes)

    @Test
    @Config(fontScale = 2.0f)
    fun `ringing max snoozes at 200 percent`() = ringing(SnoozeVariant.MaxSnoozes, suffix = "_font200")

    @Test
    fun `ringing price cap`() = ringing(SnoozeVariant.PriceCap)

    @Test
    @Config(fontScale = 2.0f)
    fun `ringing price cap at 200 percent`() = ringing(SnoozeVariant.PriceCap, suffix = "_font200")

    @Test
    fun `ringing payment pending`() = ringing(SnoozeVariant.PaymentPending)

    @Test
    @Config(fontScale = 2.0f)
    fun `ringing payment pending at 200 percent`() = ringing(SnoozeVariant.PaymentPending, suffix = "_font200")

    @Test
    fun `ringing earlier payment refunding`() = ringing(SnoozeVariant.Refunding)

    @Test
    @Config(fontScale = 2.0f)
    fun `ringing earlier payment refunding at 200 percent`() = ringing(SnoozeVariant.Refunding, suffix = "_font200")

    @Test
    fun `ringing offline`() = ringing(SnoozeVariant.Offline, shot = false)

    @Test
    fun `ringing prices not loaded`() = ringing(SnoozeVariant.PricesNotLoaded, shot = false)

    @Test
    fun `check available`() = check(SnoozeVariant.Available)

    @Test
    @Config(fontScale = 2.0f)
    fun `check available at 200 percent`() = check(SnoozeVariant.Available, suffix = "_font200")

    @Test
    fun `check test mode`() = check(SnoozeVariant.TestMode)

    @Test
    @Config(fontScale = 2.0f)
    fun `check test mode at 200 percent`() = check(SnoozeVariant.TestMode, suffix = "_font200")

    @Test
    fun `check before the first unlock`() = check(SnoozeVariant.BeforeFirstUnlock)

    @Test
    @Config(fontScale = 2.0f)
    fun `check before the first unlock at 200 percent`() = check(SnoozeVariant.BeforeFirstUnlock, suffix = "_font200")

    @Test
    fun `check max snoozes`() = check(SnoozeVariant.MaxSnoozes)

    @Test
    @Config(fontScale = 2.0f)
    fun `check max snoozes at 200 percent`() = check(SnoozeVariant.MaxSnoozes, suffix = "_font200")

    @Test
    fun `check price cap`() = check(SnoozeVariant.PriceCap)

    @Test
    @Config(fontScale = 2.0f)
    fun `check price cap at 200 percent`() = check(SnoozeVariant.PriceCap, suffix = "_font200")

    @Test
    fun `check payment pending`() = check(SnoozeVariant.PaymentPending)

    @Test
    @Config(fontScale = 2.0f)
    fun `check payment pending at 200 percent`() = check(SnoozeVariant.PaymentPending, suffix = "_font200")

    @Test
    fun `check earlier payment refunding`() = check(SnoozeVariant.Refunding)

    @Test
    @Config(fontScale = 2.0f)
    fun `check earlier payment refunding at 200 percent`() = check(SnoozeVariant.Refunding, suffix = "_font200")

    @Test
    fun `check offline`() = check(SnoozeVariant.Offline)

    @Test
    @Config(fontScale = 2.0f)
    fun `check offline at 200 percent`() = check(SnoozeVariant.Offline, suffix = "_font200")

    @Test
    fun `check prices not loaded`() = check(SnoozeVariant.PricesNotLoaded, shot = false)
}

package com.yawnandpawn.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.session.PurchaseOutcome
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.wake.CheckScreen
import com.yawnandpawn.app.ui.wake.PaymentOutcomeRow
import com.yawnandpawn.app.ui.wake.PaymentPendingRow
import com.yawnandpawn.app.ui.wake.RingingScreen
import com.yawnandpawn.app.ui.wake.SnoozeAfterPayment
import com.yawnandpawn.app.ui.wake.SnoozeOffer
import com.yawnandpawn.app.ui.wake.SnoozeUnavailableReason
import com.yawnandpawn.app.ui.wake.paymentOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 4.14 screenshots: each payment message (the Payment outcomes table, [paymentOutcome]) on Ringing and on a Math
 * check, with Snooze as the row leaves it, in Sunrise at 100% and 200% font scale. The message sits above "I'm up" (or
 * above Snooze in the check footer) and never pushes "I'm up", the check's keys or Snooze off the screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class PaymentOutcomeScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val dollar = Money.of(1, "USD")

    /** Snooze as the row leaves it on this ring. */
    private fun snoozeAfter(row: PaymentOutcomeRow): SnoozeOffer =
        when (row.snooze) {
            SnoozeAfterPayment.UnavailableOffline -> SnoozeOffer.Unavailable(SnoozeUnavailableReason.Offline)
            SnoozeAfterPayment.UnavailablePaymentPending -> SnoozeOffer.Unavailable(SnoozeUnavailableReason.PaymentPending)
            SnoozeAfterPayment.Offered -> SnoozeOffer.Available(dollar)
        }

    /** [node] is whole inside the window, without scrolling. */
    private fun assertWhole(
        node: SemanticsNodeInteraction,
        what: String,
    ) {
        val window = composeRule.onRoot().getBoundsInRoot()
        val bounds = node.assertIsDisplayed().getBoundsInRoot()
        assertTrue(bounds.top >= window.top && bounds.bottom <= window.bottom, "$what is whole on screen: $bounds in $window")
    }

    private fun shoot(
        name: String,
        row: PaymentOutcomeRow,
        text: String,
        font200: Boolean,
    ) {
        val message = row.message
        val snooze = snoozeAfter(row)
        val suffix = if (font200) "_font200" else ""
        val ringing = RingingSamples.enabledSnooze.copy(message = message, snooze = snooze)
        withScreen(PpsThemeMode.Light, content = { RingingScreen(ringing, is24Hour = false, onIntent = {}) }) {
            assertWhole(composeRule.onNodeWithText(text), "the message")
            assertWhole(composeRule.onNodeWithText("I'm up"), "I'm up")
            val messageBottom = composeRule.onNodeWithText(text).getBoundsInRoot().bottom
            assertTrue(messageBottom <= composeRule.onNodeWithText("I'm up").getBoundsInRoot().top, "the message sits above I'm up")
            composeRule.onRoot().captureRoboImage(
                "src/test/screenshots/wake_payment_${name}_ringing_sunrise$suffix.png",
                roborazziOptions = screenshotOptions,
            )
        }
        val check = CheckSamples.grace.copy(message = message, snooze = snooze)
        withScreen(PpsThemeMode.Light, content = { CheckScreen(check, onIntent = {}) }) {
            assertWhole(composeRule.onNodeWithText(text), "the message")
            assertWhole(composeRule.onNode(hasText("Check") and hasClickAction()), "the check's Check key")
            composeRule.onRoot().captureRoboImage(
                "src/test/screenshots/wake_payment_${name}_check_sunrise$suffix.png",
                roborazziOptions = screenshotOptions,
            )
        }
    }

    @Test
    fun `unlock failed`() = shoot("unlock_failed", paymentOutcome(PurchaseOutcome.UnlockFailed), UNLOCK_FAILED, font200 = false)

    @Test
    @Config(fontScale = 2.0f)
    fun `unlock failed at 200 percent`() =
        shoot("unlock_failed", paymentOutcome(PurchaseOutcome.UnlockFailed), UNLOCK_FAILED, font200 = true)

    @Test
    fun cancelled() = shoot("cancelled", paymentOutcome(PurchaseOutcome.Cancelled), CANCELLED, font200 = false)

    @Test
    @Config(fontScale = 2.0f)
    fun `cancelled at 200 percent`() = shoot("cancelled", paymentOutcome(PurchaseOutcome.Cancelled), CANCELLED, font200 = true)

    @Test
    fun error() = shoot("error", paymentOutcome(PurchaseOutcome.Failed), ERROR, font200 = false)

    @Test
    @Config(fontScale = 2.0f)
    fun `error at 200 percent`() = shoot("error", paymentOutcome(PurchaseOutcome.Failed), ERROR, font200 = true)

    @Test
    fun offline() = shoot("offline", paymentOutcome(PurchaseOutcome.Offline), OFFLINE, font200 = false)

    @Test
    @Config(fontScale = 2.0f)
    fun `offline at 200 percent`() = shoot("offline", paymentOutcome(PurchaseOutcome.Offline), OFFLINE, font200 = true)

    @Test
    fun pending() = shoot("pending", PaymentPendingRow, PENDING, font200 = false)

    @Test
    @Config(fontScale = 2.0f)
    fun `pending at 200 percent`() = shoot("pending", PaymentPendingRow, PENDING, font200 = true)

    /**
     * Review: on a 360 × 640 dp phone at 200% the pending message makes the check's footer tall; when it ends (the next
     * tap after its 10 s) its room stays, so the number pad does not move under the user's finger.
     */
    @Test
    @Config(qualifiers = "w360dp-h640dp-mdpi", fontScale = 2.0f)
    fun `the number pad stays put when the message ends on a 360 x 640 phone at 200 percent`() {
        val pending = CheckSamples.grace.copy(message = PaymentPendingRow.message, snooze = snoozeAfter(PaymentPendingRow))
        var state by mutableStateOf(pending)
        withScreen(PpsThemeMode.Light, content = { CheckScreen(state, onIntent = {}) }) {
            composeRule.waitForIdle()
            val keys = listOf("1", "5", "Check").associateWith { composeRule.onNode(hasText(it) and hasClickAction()).getBoundsInRoot() }
            state = pending.copy(message = null)
            composeRule.waitForIdle()
            composeRule.onNodeWithText(PENDING).assertDoesNotExist()
            keys.forEach { (key, before) ->
                assertEquals(before, composeRule.onNode(hasText(key) and hasClickAction()).getBoundsInRoot(), "key $key did not move")
            }
        }
    }

    private companion object {
        const val UNLOCK_FAILED = "Phone still locked. No charge."
        const val CANCELLED = "Payment cancelled. No charge."
        const val ERROR = "Payment didn't go through. No charge."
        const val OFFLINE = "No connection. No charge."
        const val PENDING =
            "Payment not confirmed yet. If it goes through before you finish, your snooze starts. " +
                "Otherwise, finish the check to stop the alarm."
    }
}

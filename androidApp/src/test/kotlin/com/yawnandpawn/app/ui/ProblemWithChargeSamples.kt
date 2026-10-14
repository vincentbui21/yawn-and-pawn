package com.yawnandpawn.app.ui

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.ui.payments.ChargeOption
import com.yawnandpawn.app.ui.payments.ChargeSnackbar
import com.yawnandpawn.app.ui.payments.ChargeStatus
import com.yawnandpawn.app.ui.payments.ProblemWithChargeUiState
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset

/** Story 4.17: "Problem with a charge?" states for the screenshot and semantics tests. */
internal object ProblemWithChargeSamples {
    private fun charge(
        key: String,
        day: Int,
        hour: Int,
        minute: Int,
        price: Money?,
        status: ChargeStatus?,
    ) = ChargeOption(
        key = key,
        orderId = "GPA.3301-2345-6789-0$key",
        purchasedAt = LocalDateTime(2026, 9, day, hour, minute),
        offset = UtcOffset(hours = 3),
        price = price,
        status = status,
    )

    /**
     * Four charges, newest first: two paid snoozes of one morning, one whose amount is not known (no price) and one not
     * used (refunded automatically by Google).
     */
    val charges =
        listOf(
            charge("4", 23, 7, 41, Money.of(2, "USD"), ChargeStatus.Snooze(2)),
            charge("3", 23, 7, 32, Money.of(1, "USD"), ChargeStatus.Snooze(1)),
            charge("2", 22, 7, 31, null, ChargeStatus.Snooze(1)),
            charge("1", 6, 7, 33, Money.of(1, "USD"), ChargeStatus.NotUsed),
        )

    /** The screen with charges behind it (it looks the same without: the charges show only in the picker). */
    val withCharges = ProblemWithChargeUiState(charges = charges, supportEmail = "vincentbui2108@gmail.com")

    /** No charge at all: "Email support" opens the email without order details. */
    val noCharges = ProblemWithChargeUiState(supportEmail = "vincentbui2108@gmail.com")

    /** The picker, newest picked. */
    val picker = withCharges.copy(picking = true, selectedKey = "4")

    /** The picker after "Email support" found no email app. */
    val pickerNoEmailApp = picker.copy(snackbar = ChargeSnackbar.NoEmailApp)

    /** "Open Google Play order history" found no browser. */
    val noBrowser = withCharges.copy(snackbar = ChargeSnackbar.NoBrowser)
}

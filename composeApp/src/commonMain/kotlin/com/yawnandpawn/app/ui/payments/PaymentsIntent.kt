package com.yawnandpawn.app.ui.payments

/** Everything the user can do on the payments screens. */
sealed interface PaymentsIntent {
    /** The back arrow or system Back: closes the picker when it is open, otherwise the screen. */
    data object Back : PaymentsIntent

    data object ProblemWithChargeClicked : PaymentsIntent

    data object PurchaseHistoryClicked : PaymentsIntent

    data object OpenOrderHistoryClicked : PaymentsIntent

    /** "Email support" on "Problem with a charge?": the picker, or the email straight away with no charge to pick. */
    data object EmailSupportClicked : PaymentsIntent

    /** A charge picked in the "Email support" picker ([ChargeOption.key]). */
    data class ChargePicked(
        val key: String,
    ) : PaymentsIntent

    /** "Email support" in the picker: the email about the picked charge. */
    data object SendEmailClicked : PaymentsIntent

    /** "Copy address" in the no-email-app note: the support address to the clipboard. */
    data object CopyAddressClicked : PaymentsIntent
}

package com.yawnandpawn.app.ui.payments

/** Everything the user can do on the payments screens. */
sealed interface PaymentsIntent {
    data object Back : PaymentsIntent

    data object ProblemWithChargeClicked : PaymentsIntent

    data object PurchaseHistoryClicked : PaymentsIntent

    data object OpenOrderHistoryClicked : PaymentsIntent

    data object EmailSupportClicked : PaymentsIntent
}

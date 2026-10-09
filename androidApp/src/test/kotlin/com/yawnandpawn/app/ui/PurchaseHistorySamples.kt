package com.yawnandpawn.app.ui

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.ui.purchases.Purchase
import com.yawnandpawn.app.ui.purchases.PurchaseHistoryUiState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** Story 4.16: Purchase history states for the screenshot and semantics tests. */
internal object PurchaseHistorySamples {
    private fun sep(day: Int) = LocalDate(2026, 9, day)

    private val standUp = LocalTime(7, 30)

    private fun usd(units: Int) = Money.of(units, "USD")

    /**
     * Every status in one month, newest first: a labelled alarm, paid snoozes (granted, consumed and reused read the
     * same), one whose amount is not known (no price), a payment not used with and without its alarm. September's
     * total counts only the used payments with a known amount ($5.00); August has nothing to total.
     */
    val mixed =
        PurchaseHistoryUiState(
            purchases =
                listOf(
                    Purchase(sep(23), standUp, 2, usd(2), alarmLabel = "Gym"),
                    Purchase(sep(23), standUp, 1, usd(1), alarmLabel = "Gym"),
                    Purchase(sep(22), standUp, 1, null),
                    Purchase(sep(10), standUp, 2, usd(2)),
                    Purchase(sep(6), standUp, null, usd(1), stranded = true),
                    Purchase(LocalDate(2026, 8, 27), alarmTime = null, snoozeNumber = null, price = null, stranded = true),
                ),
        )

    /** A trip abroad: each row keeps its own currency, and the month's total is one amount per currency. */
    val twoCurrencies =
        PurchaseHistoryUiState(
            purchases =
                listOf(
                    Purchase(sep(23), standUp, 2, Money(2_380_000, "EUR")),
                    Purchase(sep(23), standUp, 1, Money(1_190_000, "EUR")),
                    Purchase(sep(10), LocalTime(5, 45), 2, usd(2)),
                    Purchase(sep(10), LocalTime(5, 45), 1, usd(1)),
                ),
        )

    val empty = PurchaseHistoryUiState()

    val loading = PurchaseHistoryUiState(loading = true)

    val loadFailed = PurchaseHistoryUiState(loadFailed = true)
}

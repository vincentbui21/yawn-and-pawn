package com.yawnandpawn.app.ui.purchases

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.history.SessionHistoryRow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * One charge (FR-PRG-4) as a `purchase-row` shows it: when, for which alarm, which snooze of the session and the amount
 * paid. Every field but [date] can be unknown: a stranded payment may have no alarm or snooze number, and a price that
 * is only an estimate (not the intent's) is never shown (Story 4.10).
 *
 * @property date the day of the purchase, in the phone's zone.
 * @property alarmTime the time the alarm rang for; null when unknown.
 * @property snoozeNumber the snooze it bought; null for a stranded payment (its status is shown instead).
 * @property price the amount charged; null when it is not known.
 * @property stranded paid but not used: refunded automatically by Google.
 * @property alarmLabel the alarm's label, shown instead of [alarmTime]; null when the alarm has none or is gone.
 */
data class Purchase(
    val date: LocalDate,
    val alarmTime: LocalTime?,
    val snoozeNumber: Int?,
    val price: Money?,
    val stranded: Boolean = false,
    val alarmLabel: String? = null,
)

/**
 * What Purchase history renders: every charge, newest first ([purchases]); [loading] until the first read, [loadFailed]
 * when the records could not be read (never shown as the empty state).
 */
data class PurchaseHistoryUiState(
    val purchases: List<Purchase> = emptyList(),
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
)

/** Everything the user can do on Purchase history besides leaving it. */
sealed interface PurchaseHistoryIntent {
    /** "Try again" after a read failure. */
    data object RetryLoad : PurchaseHistoryIntent
}

/**
 * A month card: its [purchases] (newest first) and [paid], the amounts that count toward the month's total. A payment
 * that was not used (refunded automatically by Google) or whose amount is not known does not count. The screen shows
 * one total per currency, never a sum across currencies.
 *
 * @property month the first day of the month.
 */
data class PurchaseMonth(
    val month: LocalDate,
    val purchases: List<Purchase>,
    val paid: List<Money>,
)

/** [purchases] (newest first) in month cards, newest month first. */
fun monthsOf(purchases: List<Purchase>): List<PurchaseMonth> =
    purchases
        .groupBy { LocalDate(it.date.year, it.date.month, 1) }
        .map { (month, inMonth) -> PurchaseMonth(month, inMonth, inMonth.filterNot { it.stranded }.mapNotNull { it.price }) }

/**
 * The row of [record] in [zone] (Story 4.16). The alarm is named by its label while the alarm exists and has one,
 * otherwise by the time its session rang for ([session]), otherwise by the alarm's own time; with none of them only the
 * date shows. Granted, consumed and reused payments all read as paid snoozes; a stranded one reads "Not used, refunded
 * automatically by Google" instead of its snooze number. The amount shows only when it is what was charged
 * ([PriceSource.isAmountPaid][com.yawnandpawn.app.core.billing.PriceSource.isAmountPaid]).
 */
fun purchaseOf(
    record: PurchaseRecord,
    zone: TimeZone,
    alarms: Map<String, Alarm>,
    session: SessionHistoryRow?,
): Purchase {
    val alarm = (record.alarmId ?: session?.alarmId)?.let(alarms::get)
    val stranded = record.status == RecordStatus.Stranded
    return Purchase(
        date = record.purchasedAt.toLocalDateTime(zone).date,
        alarmTime = session?.scheduledAt?.toLocalDateTime(zone)?.time ?: alarm?.time,
        snoozeNumber = record.snoozeNumber.takeUnless { stranded },
        price = record.price.takeIf { record.priceSource.isAmountPaid },
        stranded = stranded,
        alarmLabel = alarm?.label?.trim()?.takeIf { it.isNotEmpty() },
    )
}

/** Newest purchase first, then by token hash (the order of `purchase_record` reads). */
internal val NewestPurchaseFirst: Comparator<PurchaseRecord> =
    compareByDescending<PurchaseRecord> { it.purchasedAt }.thenBy { it.tokenHash }

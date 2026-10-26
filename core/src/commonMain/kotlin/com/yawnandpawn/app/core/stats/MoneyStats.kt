package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.RecordStatus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/*
 * Story 6.2: the money paid per currency that Home, Progress, Day detail and the weekly summary show (FR-PRG-1, FR-PRG-3).
 * Pure, over the purchase records written only by `PurchaseLedger`. Amounts stay per currency and are never converted
 * (AD-8). Only a payment that stands and whose price is what was charged counts, so the totals always match Purchase
 * history (estimated prices and stranded payments never count).
 */

/** Status Granted, Consumed or Reused, and the price is what was charged. */
fun isPaidAmount(record: PurchaseRecord): Boolean = isCountedRecord(record) && record.priceSource.isAmountPaid

/** Monday through Sunday of [date]'s ISO week. */
fun isoWeekOf(date: LocalDate): ClosedRange<LocalDate> {
    val monday = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    return monday..monday.plus(DAYS_IN_WEEK - 1, DateTimeUnit.DAY)
}

/**
 * The money paid in [period], one total per currency sorted by ISO code, micros summed as `Long` and never converted;
 * empty when nothing was paid. Only [isPaidAmount] records count, so stranded payments and estimated prices never do. A
 * record falls in a week or month by the local date of its `purchasedAt` in [zone].
 */
fun moneyPaid(
    records: List<PurchaseRecord>,
    period: MoneyPeriod,
    today: LocalDate,
    zone: TimeZone,
): List<Money> {
    val week = isoWeekOf(today)
    val inPeriod: (LocalDate) -> Boolean =
        when (period) {
            MoneyPeriod.ThisWeek -> { date -> date in week }
            MoneyPeriod.ThisMonth -> { date -> date.year == today.year && date.month == today.month }
            MoneyPeriod.AllTime -> { _ -> true }
        }
    return totalsPerCurrency(records.filter { isPaidAmount(it) && inPeriod(it.purchasedAt.toLocalDateTime(zone).date) })
}

/** Granted, Consumed or Reused: a payment that stands (Stranded was refunded by Google). */
internal fun isCountedRecord(record: PurchaseRecord): Boolean =
    when (record.status) {
        RecordStatus.Granted, RecordStatus.Consumed, RecordStatus.Reused -> true
        RecordStatus.Stranded, RecordStatus.Absent -> false
    }

/** One total per currency of [records]' prices, sorted by ISO code. */
internal fun totalsPerCurrency(records: List<PurchaseRecord>): List<Money> =
    records
        .groupBy { it.price.currency }
        .map { (currency, paid) -> Money(paid.sumOf { it.price.micros }, currency) }
        .sortedBy { it.currency }

private const val DAYS_IN_WEEK = 7

package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceSource
import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** The zone the stats tests use unless a test is about zones. */
internal val StatsZone: TimeZone = TimeZone.of("Europe/Berlin")

private var sessionNumber = 0

/**
 * A history row scheduled on [day] at [at] local time in [zone] (`:core` tests may not use `:testing`, so this stands in
 * for its builders). A null [outcome] is a session still running: no end and no time to complete.
 */
internal fun aSession(
    day: LocalDate,
    outcome: SessionOutcome? = SessionOutcome.OnTime,
    at: LocalTime = LocalTime(6, 30),
    zone: TimeZone = StatsZone,
    timeToUp: Duration? = 3.minutes,
    snoozes: Int = if (outcome == SessionOutcome.Snoozed) 1 else 0,
    fallbackUsed: Boolean = false,
): SessionHistoryRow {
    val scheduledAt = LocalDateTime(day, at).toInstant(zone)
    val running = outcome == null
    return SessionHistoryRow(
        sessionId = "s${++sessionNumber}",
        alarmId = "a",
        scheduledAt = scheduledAt,
        firstRingAt = scheduledAt,
        endedAt = if (running) null else scheduledAt + (timeToUp ?: 30.minutes),
        snoozeCount = snoozes,
        checkTypes = listOf("Math"),
        timeToCompleteMs = if (running) null else timeToUp?.inWholeMilliseconds,
        fallbackUsed = fallbackUsed,
        directBoot = false,
        outcome = outcome,
    )
}

/** Days counted from [this]: `day(2)` is two days later, `day(-1)` the day before. */
internal fun LocalDate.day(offset: Int): LocalDate = plus(offset, DateTimeUnit.DAY)

private var recordNumber = 0

/**
 * A purchase record of [sessionId] for [price], bought at [at] local time in [zone]. `consumedAt` is set only for
 * Consumed and Reused, like the ledger writes it.
 */
internal fun aRecord(
    sessionId: String?,
    price: Money,
    at: LocalDateTime,
    status: RecordStatus = RecordStatus.Consumed,
    source: PriceSource = PriceSource.Intent,
    zone: TimeZone = StatsZone,
    tokenHash: String = "t${++recordNumber}",
): PurchaseRecord {
    val purchasedAt = at.toInstant(zone)
    return PurchaseRecord(
        tokenHash = tokenHash,
        orderId = null,
        productId = "snooze_usd_01",
        sessionId = sessionId,
        alarmId = "a",
        snoozeNumber = 1,
        price = price,
        priceSource = source,
        purchasedAt = purchasedAt,
        status = status,
        consumedAt = if (status == RecordStatus.Consumed || status == RecordStatus.Reused) purchasedAt + 1.minutes else null,
        updatedAt = purchasedAt,
    )
}

/** [micros] millionths of a euro. */
internal fun eur(micros: Long): Money = Money(micros, "EUR")

/** [micros] millionths of a US dollar. */
internal fun usd(micros: Long): Money = Money(micros, "USD")

package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.billing.PurchaseRecordsRead
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.history.SessionOutcome
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.measureTime

/** Story 6.2: the whole snapshot over three years of daily mornings stays well under a frame budget. */
class ProgressPerformanceTest {
    @Test
    fun `three years of history and 300 records compute in under 50 ms`() {
        val today = LocalDate(2027, 12, 31)
        val outcomes = SessionOutcome.entries
        val history =
            (0 until DAYS).map { i ->
                aSession(
                    today.day(-i),
                    outcomes[i % outcomes.size],
                    at = LocalTime(6, i % 60),
                    timeToUp = (60 + i % 600).seconds,
                    snoozes = i % 3,
                    fallbackUsed = i % 17 == 0,
                )
            }
        val records =
            (0 until RECORDS).map { i ->
                val day = today.day(-(i * DAYS / RECORDS))
                aRecord(
                    sessionId = history[i].sessionId,
                    price = if (i % 2 == 0) eur(2_990_000) else usd(1_000_000),
                    at = LocalDateTime(day, LocalTime(6, 40)),
                    status = if (i % 10 == 0) RecordStatus.Stranded else RecordStatus.Consumed,
                )
            }
        val read = PurchaseRecordsRead(records)

        repeat(WARM_UP) { progressSnapshot(history, read, today, StatsZone) }
        val fastest: Duration = (1..RUNS).minOf { TimeSource.Monotonic.measureTime { progressSnapshot(history, read, today, StatsZone) } }

        assertEquals(DAYS, progressSnapshot(history, read, today, StatsZone).history.size)
        assertTrue(fastest < 50.milliseconds, "fastest of $RUNS runs took $fastest")
    }

    private companion object {
        const val DAYS = 1_096
        const val RECORDS = 300
        const val WARM_UP = 20
        const val RUNS = 10
    }
}

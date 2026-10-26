package com.yawnandpawn.app.ui.daydetail

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceSource
import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.config.CommitmentAction
import com.yawnandpawn.app.core.config.CommitmentEvent
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionMergeRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.stats.sessionsOn
import com.yawnandpawn.app.testing.aPurchaseRecord
import com.yawnandpawn.app.testing.aSessionHistoryRow
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.progress.Outcome
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Story 6.7: Day detail from the stored facts only (Q1), in Europe/Berlin. */
class DayDetailMappingTest {
    private val zone = TimeZone.of("Europe/Berlin")
    private val day = LocalDate(2027, 3, 3)

    private fun at(
        hour: Int,
        minute: Int,
        date: LocalDate = day,
    ): Instant = LocalDateTime(date, LocalTime(hour, minute)).toInstant(zone)

    private fun t(
        hour: Int,
        minute: Int,
    ) = LocalTime(hour, minute)

    /** A finished session that rang at [hour]:[minute] on [date] and ended [minutesToEnd] later. */
    private fun session(
        id: String = "s1",
        hour: Int = 6,
        minute: Int = 30,
        date: LocalDate = day,
        minutesToEnd: Int = 3,
        outcome: SessionOutcome? = SessionOutcome.OnTime,
        snoozes: Int = 0,
        checks: List<String> = listOf("Math"),
        alarmId: String = "alarm-1",
    ): SessionHistoryRow {
        val start = at(hour, minute, date)
        val end = start + minutesToEnd.minutes
        return aSessionHistoryRow(sessionId = id, alarmId = alarmId).copy(
            scheduledAt = start,
            firstRingAt = start,
            endedAt = end,
            snoozeCount = snoozes,
            checkTypes = checks,
            timeToCompleteMs = (end - start).inWholeMilliseconds,
            outcome = outcome,
        )
    }

    private fun record(
        token: String,
        purchasedAt: Instant,
        price: Money = Money(990_000, "EUR"),
        status: RecordStatus = RecordStatus.Consumed,
        sessionId: String = "s1",
        snoozeNumber: Int? = 1,
        priceSource: PriceSource = PriceSource.Intent,
    ): PurchaseRecord =
        aPurchaseRecord(
            token = token,
            status = status,
            sessionId = sessionId,
            snoozeNumber = snoozeNumber,
            price = price,
            priceSource = priceSource,
            purchasedAt = purchasedAt,
        )

    private fun detail(
        history: List<SessionHistoryRow>,
        records: List<PurchaseRecord> = emptyList(),
        merges: Map<String, List<SessionMergeRow>> = emptyMap(),
        events: List<CommitmentEvent> = emptyList(),
        alarms: Map<String, Alarm> = mapOf("alarm-1" to anAlarm(id = "alarm-1", label = "Work")),
        date: LocalDate = day,
    ): DayDetailUiState = dayDetailOf(date, sessionsOn(history, records, date, zone), merges, events, alarms, zone)

    private fun DayDetailUiState.only(): SessionDetail = sessions.single()

    @Test
    fun `an on-time morning shows 3 min, one ring, no snooze, nothing paid and the label while the alarm exists`() {
        val shown = detail(listOf(session())).only()

        assertEquals(Outcome.OnTime, shown.outcome)
        assertEquals(t(6, 30), shown.alarmTime)
        assertEquals("Work", shown.alarmLabel)
        assertEquals(3, shown.minutesToUp)
        assertEquals(1, shown.rings)
        assertEquals(0, shown.snoozes)
        assertEquals(emptyList(), shown.paid)
        assertEquals(
            listOf(MorningEvent.Rang(t(6, 30)), MorningEvent.CheckSolved(t(6, 33), listOf(CheckType.Math))),
            shown.events,
        )

        assertNull(detail(listOf(session()), alarms = emptyMap()).only().alarmLabel, "a deleted alarm shows the time only")
        val blank = mapOf("alarm-1" to anAlarm(id = "alarm-1", label = "  "))
        assertNull(detail(listOf(session()), alarms = blank).only().alarmLabel)
    }

    @Test
    fun `a snoozed morning in two currencies lists each snooze at its time, and a stranded payment is never paid`() {
        val row = session(minutesToEnd = 22, outcome = SessionOutcome.Snoozed, snoozes = 2)
        val records =
            listOf(
                record("a", at(6, 31), Money(990_000, "EUR"), snoozeNumber = 1),
                record("b", at(6, 41), Money(1_000_000, "USD"), status = RecordStatus.Granted, snoozeNumber = 2),
                record("c", at(6, 42), Money(990_000, "EUR"), status = RecordStatus.Stranded, snoozeNumber = 3),
                record("other", at(6, 35), sessionId = "s2"),
            )

        val shown = detail(listOf(row), records).only()

        assertEquals(listOf(Money(990_000, "EUR"), Money(1_000_000, "USD")), shown.paid)
        assertEquals(3, shown.rings)
        assertEquals(2, shown.snoozes)
        assertEquals(22, shown.minutesToUp)
        assertEquals(listOf<Money?>(Money(990_000, "EUR")), shown.stranded)
        assertEquals(
            listOf(
                MorningEvent.Rang(t(6, 30)),
                MorningEvent.Snoozed(t(6, 31), minutes = null, price = Money(990_000, "EUR")),
                MorningEvent.Snoozed(t(6, 41), minutes = null, price = Money(1_000_000, "USD")),
                MorningEvent.CheckSolved(t(6, 52), listOf(CheckType.Math)),
            ),
            shown.events,
        )
    }

    @Test
    fun `an estimated price shows the snooze with no amount and is never paid, nor a stranded amount`() {
        val row = session(minutesToEnd = 12, outcome = SessionOutcome.Snoozed, snoozes = 1)
        val records =
            listOf(
                record("a", at(6, 31), priceSource = PriceSource.Snapshot),
                record("b", at(6, 32), status = RecordStatus.Stranded, priceSource = PriceSource.Snapshot),
            )

        val shown = detail(listOf(row), records).only()

        assertEquals(MorningEvent.Snoozed(t(6, 31), minutes = null, price = null), shown.events[1])
        assertEquals(emptyList(), shown.paid)
        assertEquals(listOf<Money?>(null), shown.stranded)
    }

    @Test
    fun `a reused payment bought before this ring shows no time, right after the ring`() {
        val row = session(minutesToEnd = 20, outcome = SessionOutcome.Snoozed, snoozes = 2)
        val records =
            listOf(
                record("late", at(6, 40), snoozeNumber = 2),
                record("reused", at(5, 50), status = RecordStatus.Reused, snoozeNumber = 1),
            )

        val events = detail(listOf(row), records).only().events

        assertEquals(MorningEvent.Rang(t(6, 30)), events[0])
        assertEquals(MorningEvent.Snoozed(null, minutes = null, price = Money(990_000, "EUR")), events[1])
        assertEquals(MorningEvent.Snoozed(t(6, 40), minutes = null, price = Money(990_000, "EUR")), events[2])
    }

    @Test
    fun `a missed morning stops at its end, with no check solved and no time to get up`() {
        val row = session(minutesToEnd = 30, outcome = SessionOutcome.Missed).copy(timeToCompleteMs = null)

        val shown = detail(listOf(row)).only()

        assertEquals(listOf(MorningEvent.Rang(t(6, 30)), MorningEvent.Stopped(t(7, 0))), shown.events)
        assertNull(shown.minutesToUp)
        assertEquals(1, shown.rings)

        val stored = detail(listOf(session(minutesToEnd = 30, outcome = SessionOutcome.Missed))).only()
        assertNull(stored.minutesToUp, "a missed session never shows a time to get up")
    }

    @Test
    fun `a fallback before the first unlock rang before the unlock, and the fallback check has no time just before the end`() {
        val row = session(checks = listOf("WordUnscramble")).copy(fallbackUsed = true, directBoot = true, fallbackFrom = "QrBarcode")
        val merge = SessionMergeRow("s1", "alarm-2", at(6, 35), mergedAt = at(6, 35))

        val events = detail(listOf(row.copy(endedAt = at(6, 36))), merges = mapOf("s1" to listOf(merge))).only().events

        assertEquals(
            listOf(
                MorningEvent.Rang(t(6, 30), beforeFirstUnlock = true),
                MorningEvent.Merged(t(6, 35), t(6, 35)),
                MorningEvent.FallbackUsed(null, CheckType.WordUnscramble),
                MorningEvent.CheckSolved(t(6, 36), listOf(CheckType.WordUnscramble)),
            ),
            events,
        )
    }

    @Test
    fun `merged alarms sit at their merge time among the snoozes`() {
        val row = session(minutesToEnd = 20, outcome = SessionOutcome.Snoozed, snoozes = 2)
        val records = listOf(record("a", at(6, 31), snoozeNumber = 1), record("b", at(6, 45), snoozeNumber = 2))
        val merges = listOf(SessionMergeRow("s1", "alarm-2", at(6, 40), at(6, 40)), SessionMergeRow("s1", "alarm-3", at(6, 30), at(6, 32)))

        val times = detail(listOf(row), records, merges = mapOf("s1" to merges)).only().events.map { it::class.simpleName to it.time }

        assertEquals(
            listOf(
                "Rang" to t(6, 30),
                "Snoozed" to t(6, 31),
                "Merged" to t(6, 32),
                "Merged" to t(6, 40),
                "Snoozed" to t(6, 45),
                "CheckSolved" to t(6, 50),
            ),
            times,
        )
        val merged = detail(listOf(row), records, merges = mapOf("s1" to merges)).only().events[2]
        assertEquals(MorningEvent.Merged(t(6, 32), alarmTime = t(6, 30)), merged)
    }

    @Test
    fun `a test or skipped session shows its hero only`() {
        val shown =
            detail(
                listOf(
                    session(id = "t", hour = 6, outcome = SessionOutcome.Test),
                    session(id = "k", hour = 9, minute = 0, outcome = SessionOutcome.Skipped, alarmId = "gone"),
                ),
            ).sessions

        assertEquals(listOf(Outcome.Test, Outcome.Skipped), shown.map { it.outcome })
        shown.forEach {
            assertEquals(emptyList(), it.events)
            assertEquals(0, it.rings)
            assertNull(it.minutesToUp)
            assertFalse(it.counted)
        }
        assertEquals(t(9, 0), shown[1].alarmTime)
        assertNull(shown[0].alarmLabel, "the hero shows the time only, even while the alarm has a label")
        assertNull(shown[1].alarmLabel)
    }

    @Test
    fun `missing facts are left out, never shown as zero`() {
        val open = session().copy(endedAt = null, timeToCompleteMs = null)
        val shown = detail(listOf(open)).only()
        assertEquals(listOf<MorningEvent>(MorningEvent.Rang(t(6, 30))), shown.events)
        assertNull(shown.minutesToUp)

        val unknown = detail(listOf(session(checks = listOf("Placeholder", "SomethingNew")))).only()
        assertEquals(listOf<MorningEvent>(MorningEvent.Rang(t(6, 30))), unknown.events, "no known check: no end row")

        val twoSteps = detail(listOf(session(checks = listOf("Math", "Unknown", "WordUnscramble")))).only()
        assertEquals(MorningEvent.CheckSolved(t(6, 33), listOf(CheckType.Math, CheckType.WordUnscramble)), twoSteps.events.last())
    }

    @Test
    fun `a session belongs to the day it was scheduled for, sessions are in scheduled order, and a running one is left out`() {
        val lateNight = session(id = "late", hour = 23, minute = 50, date = LocalDate(2027, 3, 2), minutesToEnd = 20)
        val shown = detail(listOf(lateNight), date = LocalDate(2027, 3, 2)).only()
        assertEquals(MorningEvent.CheckSolved(t(0, 10), listOf(CheckType.Math)), shown.events.last())
        assertTrue(detail(listOf(lateNight)).nothingLogged, "not on the day it ended")

        val two =
            detail(
                listOf(
                    session(id = "b", hour = 7, minute = 0),
                    session(id = "a", hour = 5, minute = 45),
                    session(id = "running", hour = 6, outcome = null),
                ),
            )
        assertEquals(listOf(t(5, 45), t(7, 0)), two.sessions.map { it.alarmTime })
    }

    @Test
    fun `turned-off and deleted alarms of the day are notes in occurrence order, other days left out`() {
        val events =
            listOf(
                CommitmentEvent("e1", "alarm-1", at(9, 0), CommitmentAction.Deleted, at(8, 0)),
                CommitmentEvent("e2", "alarm-2", at(7, 0), CommitmentAction.Disabled, at(6, 0)),
                CommitmentEvent("e3", "alarm-3", at(7, 0, LocalDate(2027, 3, 4)), CommitmentAction.Disabled, at(6, 0)),
            )

        val shown = detail(listOf(session()), events = events)

        assertEquals(listOf(AlarmChange(t(7, 0), deleted = false), AlarmChange(t(9, 0), deleted = true)), shown.changes)

        val changesOnly = detail(emptyList(), events = events)
        assertEquals(emptyList(), changesOnly.sessions)
        assertFalse(changesOnly.nothingLogged)
    }

    @Test
    fun `a day with no session and no change has nothing logged`() {
        assertTrue(detail(emptyList()).nothingLogged)
        assertFalse(detail(listOf(session())).nothingLogged)
    }
}

package com.yawnandpawn.app.ui

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.daydetail.AlarmChange
import com.yawnandpawn.app.ui.daydetail.DayDetailUiState
import com.yawnandpawn.app.ui.daydetail.MorningEvent
import com.yawnandpawn.app.ui.daydetail.SessionDetail
import com.yawnandpawn.app.ui.progress.Outcome
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Story 6.7 screen states of Day detail as the app builds them from stored facts only (Q1): what `dayDetailOf` gives for
 * an on-time morning, a snoozed morning in two currencies with a payment Google refunded, a missed morning, a fallback
 * before the first unlock, a merged alarm, a test and a skipped session, a turned-off note, loading and the load failure.
 * Fixed EUR and USD amounts, whatever the locale.
 */
object DayDetailSamples {
    private val date = LocalDate(2027, 3, 3)

    private fun t(
        hour: Int,
        minute: Int,
    ) = LocalTime(hour, minute)

    private val eur = Money(990_000, "EUR")
    private val usd = Money(1_000_000, "USD")

    private fun counted(
        outcome: Outcome,
        events: List<MorningEvent>,
        snoozes: Int = 0,
        paid: List<Money> = emptyList(),
        minutesToUp: Int? = null,
        stranded: List<Money?> = emptyList(),
        label: String? = "Work",
    ) = SessionDetail(
        alarmTime = t(6, 30),
        alarmLabel = label,
        outcome = outcome,
        events = events,
        snoozes = snoozes,
        rings = 1 + snoozes,
        paid = paid,
        minutesToUp = minutesToUp,
        stranded = stranded,
    )

    private fun day(
        vararg sessions: SessionDetail,
        changes: List<AlarmChange> = emptyList(),
    ) = DayDetailUiState(date = date, sessions = sessions.toList(), changes = changes)

    val onTime =
        day(
            counted(
                Outcome.OnTime,
                listOf(MorningEvent.Rang(t(6, 30)), MorningEvent.CheckSolved(t(6, 33), listOf(CheckType.Math))),
                minutesToUp = 3,
            ),
        )

    val snoozedTwoCurrenciesStranded =
        day(
            counted(
                Outcome.Snoozed,
                listOf(
                    MorningEvent.Rang(t(6, 30)),
                    MorningEvent.Snoozed(t(6, 31), minutes = null, price = eur),
                    MorningEvent.Snoozed(t(6, 41), minutes = null, price = usd),
                    MorningEvent.CheckSolved(t(6, 48), listOf(CheckType.Math, CheckType.WordUnscramble)),
                ),
                snoozes = 2,
                paid = listOf(eur, usd),
                minutesToUp = 18,
                stranded = listOf(eur),
            ),
        )

    val missed =
        day(
            counted(
                Outcome.Missed,
                listOf(
                    MorningEvent.Rang(t(6, 30)),
                    MorningEvent.Snoozed(null, minutes = null, price = null),
                    MorningEvent.Stopped(t(7, 10)),
                ),
                snoozes = 1,
                label = null,
            ),
        )

    val fallbackDirectBoot =
        day(
            counted(
                Outcome.OnTime,
                listOf(
                    MorningEvent.Rang(t(6, 30), beforeFirstUnlock = true),
                    MorningEvent.FallbackUsed(null, CheckType.Math),
                    MorningEvent.CheckSolved(t(6, 30), listOf(CheckType.Math)),
                ),
                minutesToUp = 0,
            ),
        )

    val merged =
        day(
            counted(
                Outcome.OnTime,
                listOf(
                    MorningEvent.Rang(t(6, 30)),
                    MorningEvent.Merged(t(6, 35), t(6, 35)),
                    MorningEvent.CheckSolved(t(6, 36), listOf(CheckType.MemorySequence)),
                ),
                minutesToUp = 6,
            ),
        )

    val test = day(SessionDetail(t(23, 40), null, Outcome.Test, rings = 0, snoozes = 0, minutesToUp = null))

    val skipped = day(SessionDetail(t(9, 0), null, Outcome.Skipped, rings = 0, snoozes = 0, minutesToUp = null))

    val turnedOffNote = onTime.copy(changes = listOf(AlarmChange(t(7, 0), deleted = false), AlarmChange(t(9, 0), deleted = true)))

    val loading = DayDetailUiState(date = date, loading = true)

    val loadFailed = DayDetailUiState(date = date, loadFailed = true)
}

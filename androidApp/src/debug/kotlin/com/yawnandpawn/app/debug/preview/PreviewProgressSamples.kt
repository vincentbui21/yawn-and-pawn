package com.yawnandpawn.app.debug.preview

import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.daydetail.AlarmChange
import com.yawnandpawn.app.ui.daydetail.DayDetailUiState
import com.yawnandpawn.app.ui.daydetail.MorningEvent
import com.yawnandpawn.app.ui.daydetail.SessionDetail
import com.yawnandpawn.app.ui.format.Money
import com.yawnandpawn.app.ui.progress.CalendarDay
import com.yawnandpawn.app.ui.progress.CalendarMonth
import com.yawnandpawn.app.ui.progress.DaySelection
import com.yawnandpawn.app.ui.progress.Insight
import com.yawnandpawn.app.ui.progress.Outcome
import com.yawnandpawn.app.ui.progress.ProgressStats
import com.yawnandpawn.app.ui.progress.ProgressUiState
import com.yawnandpawn.app.ui.progress.RING_DAYS
import com.yawnandpawn.app.ui.progress.RingDay
import com.yawnandpawn.app.ui.purchases.Purchase
import com.yawnandpawn.app.ui.purchases.PurchaseHistoryUiState
import com.yawnandpawn.app.ui.reliability.ChecklistItem
import com.yawnandpawn.app.ui.reliability.ChecklistRow
import com.yawnandpawn.app.ui.reliability.ItemStatus
import com.yawnandpawn.app.ui.reliability.ReliabilityUiState
import com.yawnandpawn.app.ui.settings.SettingsPane
import com.yawnandpawn.app.ui.settings.SettingsUiState
import com.yawnandpawn.app.ui.settings.WeakeningNote
import com.yawnandpawn.app.ui.you.YouUiState
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus

/**
 * Fake data for design preview round 2 (progress and settings), from EXPERIENCE.md Key Flows F6, F7 and F9: Linh's
 * September with a 5-day streak (best 12), a few snoozes, a missed morning, a fallback check and a test alarm. Prices go through
 * [PreviewSamples.price], in the phone's currency.
 */
object PreviewProgressSamples {
    private val price = PreviewSamples::price

    private fun sep(day: Int) = LocalDate(YEAR, SEPTEMBER, day)

    /** "Today" in the preview (the ringing screen shows the same Monday). */
    private val today = sep(TODAY)

    // Progress -----------------------------------------------------------------------------------------------------

    private val septemberDays =
        buildList {
            add(CalendarDay(sep(1), Outcome.OnTime))
            add(CalendarDay(sep(2), Outcome.Snoozed))
            add(CalendarDay(sep(3), Outcome.OnTime))
            add(CalendarDay(sep(4), Outcome.OnTime))
            add(CalendarDay(sep(7), Outcome.Missed))
            add(CalendarDay(sep(8), Outcome.OnTime))
            add(CalendarDay(sep(9), Outcome.OnTime, fallbackUsed = true))
            add(CalendarDay(sep(10), Outcome.Snoozed))
            add(CalendarDay(sep(11), Outcome.OnTime))
            add(CalendarDay(sep(12), Outcome.Test))
            for (day in 14..18) add(CalendarDay(sep(day), Outcome.OnTime))
            add(CalendarDay(sep(19), Outcome.Skipped))
            add(CalendarDay(sep(21), Outcome.OnTime, sessions = 2))
            add(CalendarDay(sep(22), Outcome.Snoozed))
            add(CalendarDay(sep(23), Outcome.Snoozed))
            for (day in 24..28) add(CalendarDay(sep(day), Outcome.OnTime))
        }

    val september = CalendarMonth(firstDay = sep(1), days = septemberDays, today = today, hasPrevious = true, hasNext = false)

    /** The month before, for "Previous month" in the tap-through: the first weeks with the app. */
    val august =
        CalendarMonth(
            firstDay = LocalDate(2026, 8, 1),
            days =
                (3..31).filter { LocalDate(2026, 8, it).dayOfWeek.ordinal < WEEKEND_ORDINAL }.map { day ->
                    CalendarDay(LocalDate(2026, 8, day), if (day % 3 == 0) Outcome.Snoozed else Outcome.OnTime)
                },
            today = today,
            hasPrevious = false,
            hasNext = true,
        )

    /** The last 30 mornings, Aug 30 to today, oldest first, from the two months' calendars. */
    private val ring: List<RingDay> =
        (RING_DAYS - 1 downTo 0).map { back ->
            val date = today.minus(back, DateTimeUnit.DAY)
            val day = (august.days + septemberDays).firstOrNull { it.date == date }
            RingDay(date, day?.outcome, day?.fallbackUsed ?: false)
        }

    /** Snoozes of the snoozed mornings Tue 22 and Wed 23 (their Day detail and purchases). */
    private val snoozesOn = mapOf(sep(FIRST_OF_WEEK) to 1, sep(FIRST_OF_WEEK + 1) to 2)

    val progress =
        ProgressUiState(
            today = today,
            ring = ring,
            stats = ProgressStats(currentStreak = 5, bestStreak = 12, onTime30Days = 80, averageMinutesToUp = 3, snoozes30Days = 8),
            calendar = september,
            paidThisMonth = price(9),
            insight = Insight.FastestOnWeekdays,
        )

    /** A ring dot tapped once: its label chip under the ring. */
    val progressDotChip = progress.copy(selection = DaySelection(sep(SNOOZED_DAY), Outcome.Snoozed, inCalendar = false))

    /** A calendar day tapped once: its label chip under the calendar. */
    val progressCalendarChip = progress.copy(selection = DaySelection(sep(SNOOZED_DAY), Outcome.Snoozed, inCalendar = true))

    val progressEmpty =
        ProgressUiState(
            today = today,
            calendar = september.copy(days = emptyList(), hasPrevious = false),
        )

    // Day detail ---------------------------------------------------------------------------------------------------

    private val standUp = LocalTime(7, 30)

    private fun t(
        hour: Int,
        minute: Int,
    ) = LocalTime(hour, minute)

    /**
     * A morning at [alarm] with [snoozes] paid 9-minute snoozes (snooze n costs B x n), then "I'm up" a minute after the
     * last ring and the check solved a minute later: every time, price and count follows from the one before.
     */
    private fun morning(
        alarm: LocalTime,
        snoozes: Int,
        check: CheckType = CheckType.Math,
        tries: Int = 1,
    ): List<MorningEvent> =
        buildList {
            var minute = alarm.hour * MINUTES_PER_HOUR + alarm.minute

            fun at(m: Int) = LocalTime(m / MINUTES_PER_HOUR, m % MINUTES_PER_HOUR)
            add(MorningEvent.Rang(at(minute)))
            for (n in 1..snoozes) {
                add(MorningEvent.Snoozed(at(minute + 1), SNOOZE_MINUTES, price(n)))
                minute += SNOOZE_MINUTES + 1
                add(MorningEvent.Rang(at(minute), again = true))
            }
            add(MorningEvent.ImUp(at(minute + 1)))
            add(MorningEvent.CheckSolved(at(minute + 2), check, tries))
        }

    /** F4 and F9: two paid snoozes. 07:30 rang, 07:31 snoozed, 07:40 again, 07:41 snoozed, 07:50 again, 07:51 up, 07:52 solved: 22 min. */
    val daySnoozed =
        DayDetailUiState(
            date = sep(10),
            sessions = listOf(SessionDetail(standUp, "Stand-up", Outcome.Snoozed, morning(standUp, snoozes = 2))),
        )

    /** F5: the camera could not start, the fallback check (Math) took two tries; a 7:35 alarm merged in; a logged change. */
    val dayFallback =
        DayDetailUiState(
            date = sep(9),
            sessions =
                listOf(
                    SessionDetail(
                        standUp,
                        "Stand-up",
                        Outcome.OnTime,
                        listOf(
                            MorningEvent.Rang(t(7, 30)),
                            MorningEvent.ImUp(t(7, 31)),
                            MorningEvent.FallbackUsed(t(7, 32), CheckType.Math),
                            MorningEvent.Merged(t(7, 35), t(7, 35)),
                            MorningEvent.CheckSolved(t(7, 36), CheckType.Math, tries = 2),
                        ),
                    ),
                ),
            changes = listOf(AlarmChange(LocalTime(9, 0), deleted = false)),
        )

    /** F8: rang before the first unlock after a restart, so QR/Barcode became Math; quiet time ran out once. */
    val dayBeforeUnlock =
        DayDetailUiState(
            date = sep(16),
            sessions =
                listOf(
                    SessionDetail(
                        standUp,
                        "Stand-up",
                        Outcome.OnTime,
                        listOf(
                            MorningEvent.Rang(t(7, 30), beforeFirstUnlock = true),
                            MorningEvent.CheckSwitched(t(7, 30), CheckType.Math),
                            MorningEvent.ImUp(t(7, 31)),
                            MorningEvent.QuietTimeRanOut(t(7, 32)),
                            MorningEvent.CheckSolved(t(7, 33), CheckType.Math),
                        ),
                    ),
                ),
        )

    val dayTwoSessions =
        DayDetailUiState(
            date = sep(21),
            sessions =
                listOf(
                    SessionDetail(t(5, 45), "Early shift", Outcome.OnTime, morning(t(5, 45), snoozes = 0)),
                    SessionDetail(
                        standUp,
                        "Stand-up",
                        Outcome.OnTime,
                        morning(standUp, snoozes = 0, check = CheckType.WordUnscramble, tries = 2),
                    ),
                ),
        )

    /** Rang at 7:30, no interaction, stopped at 8:00; the 6:15 alarm was deleted that day. */
    val dayMissed =
        DayDetailUiState(
            date = sep(7),
            sessions =
                listOf(
                    SessionDetail(standUp, "Stand-up", Outcome.Missed, listOf(MorningEvent.Rang(t(7, 30)), MorningEvent.Stopped(t(8, 0)))),
                ),
            changes = listOf(AlarmChange(LocalTime(6, 15), deleted = true)),
        )

    val dayTest = DayDetailUiState(date = sep(12), sessions = listOf(SessionDetail(LocalTime(23, 40), null, Outcome.Test)))

    val daySkipped = DayDetailUiState(date = sep(19), sessions = listOf(SessionDetail(LocalTime(9, 0), null, Outcome.Skipped)))

    /**
     * The Day detail for a tapped ring dot or calendar day: a sample when there is one, otherwise a morning with that
     * day's outcome (Tue 22 one snooze, Wed 23 two, other snoozed days one).
     */
    fun dayDetail(date: LocalDate): DayDetailUiState {
        listOf(daySnoozed, dayFallback, dayBeforeUnlock, dayTwoSessions, dayMissed, dayTest, daySkipped)
            .firstOrNull { it.date == date }
            ?.let { return it }
        val outcome = (august.days + septemberDays).firstOrNull { it.date == date }?.outcome ?: Outcome.OnTime
        val snoozes = if (outcome == Outcome.Snoozed) (snoozesOn[date] ?: 1).coerceAtLeast(1) else 0
        val events =
            when (outcome) {
                Outcome.Missed -> listOf(MorningEvent.Rang(standUp), MorningEvent.Stopped(t(8, 0)))
                Outcome.Test, Outcome.Skipped -> emptyList()
                else -> morning(standUp, snoozes)
            }
        return DayDetailUiState(date = date, sessions = listOf(SessionDetail(standUp, "Stand-up", outcome, events)))
    }

    // Purchase history ---------------------------------------------------------------------------------------------

    val purchases =
        PurchaseHistoryUiState(
            purchases =
                listOf(
                    Purchase(sep(23), standUp, 2, price(2)),
                    Purchase(sep(23), standUp, 1, price(1)),
                    Purchase(sep(22), standUp, 1, price(1)),
                    Purchase(sep(10), standUp, 2, price(2)),
                    Purchase(sep(10), standUp, 1, price(1)),
                    Purchase(sep(2), standUp, 2, price(2)),
                    Purchase(sep(2), standUp, 1, price(1)),
                    Purchase(LocalDate(2026, 8, 27), standUp, 1, price(1), stranded = true),
                    Purchase(LocalDate(2026, 8, 12), LocalTime(5, 45), 3, price(3)),
                    Purchase(LocalDate(2026, 8, 12), LocalTime(5, 45), 2, price(2)),
                    Purchase(LocalDate(2026, 8, 12), LocalTime(5, 45), 1, price(1)),
                ),
        )

    val purchasesEmpty = PurchaseHistoryUiState()

    // Settings -----------------------------------------------------------------------------------------------------

    val settings = SettingsUiState(baseFee = price(1), lowerFee = null, higherFee = price(2), vibrateDuringQuietTime = true)

    val settingsReliability = settings.copy(reliabilityProblem = true)

    // You -----------------------------------------------------------------------------------------------------------

    val you = YouUiState(appVersion = "0.1.0")

    val youDeleteDialog = you.copy(showDeleteDialog = true)

    val youNoBrowser = you.copy(noBrowser = true)

    val settingsSession = settings.copy(sessionInProgress = true)

    val settingsBaseFee = settings.copy(pane = SettingsPane.BaseFee)

    /** F6: lowered from 3 to 1 at 23:40; the lower fee waits for tomorrow's 7:30 alarm. */
    val settingsBaseFeeWeakening = settingsBaseFee.copy(weakening = WeakeningNote(standUp))

    /** Prices never loaded: US dollar tiers with the approximate-price note. */
    val settingsBaseFeeApproximate =
        settingsBaseFee.copy(baseFee = Money.of(1, "USD"), higherFee = Money.of(2, "USD"), pricesApproximate = true)

    val settingsMaxSnoozes = settings.copy(pane = SettingsPane.MaxSnoozes)

    val settingsSnoozeLength = settings.copy(pane = SettingsPane.SnoozeLength)

    val settingsQuietTime = settings.copy(pane = SettingsPane.QuietTime)

    // Reliability checklist and payments ------------------------------------------------------------------------------

    /** F7 after a system update: battery optimization turned back on, full-screen alarm and the maker's settings missing. */
    val reliabilityMissing =
        ReliabilityUiState(
            rows =
                listOf(
                    ChecklistRow(ChecklistItem.Notifications, ItemStatus.Ok),
                    ChecklistRow(ChecklistItem.FullScreen, ItemStatus.Missing),
                    ChecklistRow(ChecklistItem.ExactAlarms, ItemStatus.Ok),
                    ChecklistRow(ChecklistItem.DoNotDisturb, ItemStatus.Ok),
                    ChecklistRow(ChecklistItem.Battery, ItemStatus.Revoked),
                    ChecklistRow(ChecklistItem.Manufacturer, ItemStatus.Missing),
                    ChecklistRow(ChecklistItem.Camera, ItemStatus.Ok),
                    ChecklistRow(ChecklistItem.TestAlarm, ItemStatus.Missing),
                ),
        )

    val reliabilityAllOk = ReliabilityUiState(rows = reliabilityMissing.rows.map { it.copy(status = ItemStatus.Ok) })

    val reliabilityManufacturer = reliabilityMissing.copy(showManufacturerSteps = true)

    /** The most one morning can cost with the default 5 snoozes: B x (1 + 2 + 3 + 4 + 5). */
    val priceCap: Money = price(CAP_MULTIPLE)

    private const val CAP_MULTIPLE = 15

    private const val YEAR = 2026

    /** Wednesday 23: two snoozes. */
    private const val SNOOZED_DAY = 23

    /** Tuesday 22, the first of the two snoozed mornings. */
    private const val FIRST_OF_WEEK = 22

    /** The fake alarms' snooze length. */
    private const val SNOOZE_MINUTES = 9

    private const val MINUTES_PER_HOUR = 60

    private const val SEPTEMBER = 9

    private const val TODAY = 28

    /** `DayOfWeek.SATURDAY.ordinal`: August's sample mornings are weekdays. */
    private const val WEEKEND_ORDINAL = 5
}

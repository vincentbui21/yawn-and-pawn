package com.yawnandpawn.app.debug.preview

import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.daydetail.AlarmChange
import com.yawnandpawn.app.ui.daydetail.DayDetailUiState
import com.yawnandpawn.app.ui.daydetail.SessionDetail
import com.yawnandpawn.app.ui.format.Money
import com.yawnandpawn.app.ui.progress.CalendarDay
import com.yawnandpawn.app.ui.progress.CalendarMonth
import com.yawnandpawn.app.ui.progress.MoneyPaid
import com.yawnandpawn.app.ui.progress.Outcome
import com.yawnandpawn.app.ui.progress.ProgressStats
import com.yawnandpawn.app.ui.progress.ProgressUiState
import com.yawnandpawn.app.ui.progress.WeekSnoozes
import com.yawnandpawn.app.ui.purchases.Purchase
import com.yawnandpawn.app.ui.purchases.PurchaseHistoryUiState
import com.yawnandpawn.app.ui.reliability.ChecklistItem
import com.yawnandpawn.app.ui.reliability.ChecklistRow
import com.yawnandpawn.app.ui.reliability.ItemStatus
import com.yawnandpawn.app.ui.reliability.ReliabilityUiState
import com.yawnandpawn.app.ui.settings.SettingsPane
import com.yawnandpawn.app.ui.settings.SettingsUiState
import com.yawnandpawn.app.ui.settings.WeakeningNote
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Fake data for design preview round 2 (progress and settings), from EXPERIENCE.md Key Flows F6, F7 and F9: Linh's
 * September with a 12-day streak, a few snoozes, a missed morning, a fallback check and a test alarm. Prices go through
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
            add(CalendarDay(sep(21), Outcome.OnTime))
            add(CalendarDay(sep(22), Outcome.OnTime, sessions = 2))
            for (day in 23..25) add(CalendarDay(sep(day), Outcome.OnTime))
            add(CalendarDay(sep(28), Outcome.OnTime))
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

    private val weeks =
        listOf(
            WeekSnoozes(LocalDate(2026, 8, 3), 6),
            WeekSnoozes(LocalDate(2026, 8, 10), 4),
            WeekSnoozes(LocalDate(2026, 8, 17), 5),
            WeekSnoozes(LocalDate(2026, 8, 24), 3),
            WeekSnoozes(LocalDate(2026, 8, 31), 1),
            WeekSnoozes(sep(7), 2),
            WeekSnoozes(sep(14), 0),
            WeekSnoozes(sep(21), 0),
        )

    val progress =
        ProgressUiState(
            stats = ProgressStats(currentStreak = 12, bestStreak = 12, onTime7Days = 100, onTime30Days = 86, averageMinutesToUp = 3),
            weeks = weeks,
            calendar = september,
            money = MoneyPaid(thisWeek = price(0), thisMonth = price(3), allTime = price(21)),
            canExport = true,
        )

    /** A bar tapped: the first week, 6 snoozes. */
    val progressWeekSelected = progress.copy(selectedWeek = 0)

    val progressEmpty = ProgressUiState()

    // Day detail ---------------------------------------------------------------------------------------------------

    private val standUp = LocalTime(7, 30)

    /** F9: one snooze, paid, Math, 12 minutes to up. */
    val daySnoozed =
        DayDetailUiState(
            date = sep(10),
            sessions =
                listOf(
                    SessionDetail(
                        standUp,
                        "Stand-up",
                        Outcome.Snoozed,
                        rings = 2,
                        snoozes = 1,
                        paid = price(1),
                        checks = listOf(CheckType.Math),
                        minutesToUp = 12,
                    ),
                ),
        )

    /** F5 and F8: the fallback check, a ring before the first unlock, a merged alarm and a logged change. */
    val dayFallback =
        DayDetailUiState(
            date = sep(9),
            sessions =
                listOf(
                    SessionDetail(
                        standUp,
                        "Stand-up",
                        Outcome.OnTime,
                        checks = listOf(CheckType.QrBarcode, CheckType.Math),
                        minutesToUp = 0,
                        fallbackUsed = true,
                        rangBeforeFirstUnlock = true,
                        mergedAlarmAt = LocalTime(7, 35),
                    ),
                ),
            changes = listOf(AlarmChange(LocalTime(9, 0), deleted = false)),
        )

    val dayTwoSessions =
        DayDetailUiState(
            date = sep(22),
            sessions =
                listOf(
                    SessionDetail(LocalTime(5, 45), "Early shift", Outcome.OnTime, checks = listOf(CheckType.Math), minutesToUp = 1),
                    SessionDetail(standUp, "Stand-up", Outcome.OnTime, checks = listOf(CheckType.Math), minutesToUp = 4),
                ),
        )

    val dayMissed =
        DayDetailUiState(
            date = sep(7),
            sessions = listOf(SessionDetail(standUp, "Stand-up", Outcome.Missed, rings = 1, checks = listOf(CheckType.Math))),
            changes = listOf(AlarmChange(LocalTime(6, 15), deleted = true)),
        )

    val dayTest = DayDetailUiState(date = sep(12), sessions = listOf(SessionDetail(LocalTime(23, 40), null, Outcome.Test)))

    val daySkipped = DayDetailUiState(date = sep(19), sessions = listOf(SessionDetail(LocalTime(9, 0), null, Outcome.Skipped)))

    /** The Day detail for a tapped calendar day: a sample when there is one, otherwise a plain on-time morning. */
    fun dayDetail(date: LocalDate): DayDetailUiState =
        listOf(daySnoozed, dayFallback, dayTwoSessions, dayMissed, dayTest, daySkipped).firstOrNull { it.date == date }
            ?: DayDetailUiState(
                date = date,
                sessions = listOf(SessionDetail(standUp, "Stand-up", Outcome.OnTime, checks = listOf(CheckType.Math), minutesToUp = 3)),
            )

    // Purchase history ---------------------------------------------------------------------------------------------

    val purchases =
        PurchaseHistoryUiState(
            purchases =
                listOf(
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

    val settingsDeleteDialog = settings.copy(showDeleteDialog = true)

    val settingsNoBrowser = settings.copy(noBrowser = true)

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

    private const val SEPTEMBER = 9

    private const val TODAY = 28

    /** `DayOfWeek.SATURDAY.ordinal`: August's sample mornings are weekdays. */
    private const val WEEKEND_ORDINAL = 5
}

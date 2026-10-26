package com.yawnandpawn.app.debug.preview

import androidx.compose.runtime.Composable
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.daydetail.AlarmChange
import com.yawnandpawn.app.ui.daydetail.DayDetailScreen
import com.yawnandpawn.app.ui.daydetail.DayDetailUiState
import com.yawnandpawn.app.ui.daydetail.MorningEvent
import com.yawnandpawn.app.ui.daydetail.SessionDetail
import com.yawnandpawn.app.ui.home.HomeHero
import com.yawnandpawn.app.ui.home.HomeScreen
import com.yawnandpawn.app.ui.progress.CalendarDay
import com.yawnandpawn.app.ui.progress.Outcome
import com.yawnandpawn.app.ui.progress.ProgressScreen
import com.yawnandpawn.app.ui.progress.ProgressStats
import com.yawnandpawn.app.ui.progress.ProgressUiState
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Design preview round 5: the Epic 6 states the owner looks at before the build (epic-6-context.md, "Need an owner look
 * before the build"; docs/design-preview/round-5-epic-6.md):
 * - money in two currencies on the Home hero and the Progress "Money paid" card (one total per currency, never added
 *   across currencies, AD-8);
 * - Day detail with only the facts the session history holds (owner question Q1, default): the first ring ("before your
 *   first unlock" with Direct Boot), each snooze at its purchase time with its price, merged alarms, the fallback check
 *   (no time), the check solved at the end time (no try count), or "Stopped after 30 minutes". Rings are 1 + snoozes. The
 *   round 2 `day-*` mock-ups stay for comparison;
 * - the ring centre with a streak over 30 (owner question Q2, default): the number and "day streak", no "/ 30".
 *
 * Screenshots go to docs/design-preview/round-5/ (PreviewScreenshotTest), so the owner can look at them as files.
 */
object PreviewRound5 {
    private val p = PreviewProgressSamples

    private fun item(
        id: String,
        group: String,
        title: String,
        tall: Boolean = true,
        render: @Composable (is24Hour: Boolean) -> Unit,
    ) = PreviewItem(id, EPIC_6_ROUND, group, title, primary = true, tall = tall, render = render)

    private fun progress(
        id: String,
        title: String,
        state: ProgressUiState,
    ) = item(id, "Progress", title) {
        AppShell(selected = AppTab.Progress, onSelect = {}) { ProgressScreen(state = state, onIntent = {}) }
    }

    private fun day(
        id: String,
        title: String,
        state: DayDetailUiState,
    ) = item(id, "Day detail", title) { is24 -> DayDetailScreen(state = state, is24Hour = is24, onBack = {}) }

    // Money in two currencies --------------------------------------------------------------------------------------

    /**
     * Linh moved, so her Play country (and the price currency) changed mid-month: euros before, US dollars after. Fixed
     * currencies, whatever the phone's locale, so the two always differ.
     */
    private fun eur(micros: Long) = Money(micros, "EUR")

    private fun usd(micros: Long) = Money(micros, "USD")

    val homePaidTwoCurrencies =
        PreviewSamples.homeList.copy(hero = HomeHero(streakDays = 1, paidThisWeek = listOf(eur(EUR_WEEK), usd(USD_WEEK))))

    val progressMoneyTwoCurrencies = p.progress.copy(paidThisMonth = listOf(eur(EUR_MONTH), usd(USD_WEEK)))

    // Streak over 30 -----------------------------------------------------------------------------------------------

    /** Every morning on time since Aug 18: a 42-day streak, so all 30 ring dots are on time and nothing was paid. */
    val progressStreakOver30 =
        p.progress.copy(
            ring = p.progress.ring.map { it.copy(outcome = Outcome.OnTime, fallbackUsed = false) },
            stats =
                ProgressStats(
                    currentStreak = LONG_STREAK,
                    bestStreak = LONG_STREAK,
                    onTime30Days = 100,
                    averageMinutesToUp = 2,
                    snoozes30Days = 0,
                ),
            calendar = p.september.copy(days = (1..TODAY).map { CalendarDay(sep(it), Outcome.OnTime) }),
            paidThisMonth = emptyList(),
        )

    // Day detail from stored facts only ----------------------------------------------------------------------------

    private fun sep(day: Int) = LocalDate(YEAR, SEPTEMBER, day)

    private fun t(
        hour: Int,
        minute: Int,
    ) = LocalTime(hour, minute)

    private val standUp = t(7, 30)

    /**
     * `day-snoozed` from history: first ring 7:30, the two snooze purchases at 7:31 and 7:41 with their prices, the end
     * at 7:52. No "Rang again", "I'm up" or snooze length; rings 1 + 2 = 3.
     */
    val factsSnoozed =
        DayDetailUiState(
            date = sep(10),
            sessions =
                listOf(
                    SessionDetail(
                        standUp,
                        "Stand-up",
                        Outcome.Snoozed,
                        listOf(
                            MorningEvent.Rang(t(7, 30)),
                            MorningEvent.Snoozed(t(7, 31), minutes = null, price = PreviewSamples.price(1)),
                            MorningEvent.Snoozed(t(7, 41), minutes = null, price = PreviewSamples.price(2)),
                            MorningEvent.CheckSolved(t(7, 52), CheckType.Math, tries = null),
                        ),
                    ),
                ),
        )

    /** `day-fallback` from history: the 7:35 merge has its time, the fallback check has none (shown just before the end). */
    val factsFallback =
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
                            MorningEvent.Merged(t(7, 35), t(7, 35)),
                            MorningEvent.FallbackUsed(null, CheckType.Math),
                            MorningEvent.CheckSolved(t(7, 36), CheckType.Math, tries = null),
                        ),
                    ),
                ),
            changes = listOf(AlarmChange(LocalTime(9, 0), deleted = false)),
        )

    /** `day-before-unlock` from history: the Direct Boot flag marks the first ring; no check switch, no quiet time. */
    val factsBeforeUnlock =
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
                            MorningEvent.CheckSolved(t(7, 33), CheckType.Math, tries = null),
                        ),
                    ),
                ),
        )

    /** `day-two-sessions` from history: each session its first ring and its end. */
    val factsTwoSessions =
        DayDetailUiState(
            date = sep(21),
            sessions =
                listOf(
                    SessionDetail(
                        t(5, 45),
                        "Early shift",
                        Outcome.OnTime,
                        listOf(MorningEvent.Rang(t(5, 45)), MorningEvent.CheckSolved(t(5, 47), CheckType.Math, tries = null)),
                    ),
                    SessionDetail(
                        standUp,
                        "Stand-up",
                        Outcome.OnTime,
                        listOf(MorningEvent.Rang(t(7, 30)), MorningEvent.CheckSolved(t(7, 32), CheckType.WordUnscramble, tries = null)),
                    ),
                ),
        )

    /** One snooze, then no interaction: the ring, the snooze purchase and "Stopped after 30 minutes" at the end time. */
    val factsMissedAfterSnooze =
        DayDetailUiState(
            date = sep(7),
            sessions =
                listOf(
                    SessionDetail(
                        standUp,
                        "Stand-up",
                        Outcome.Missed,
                        listOf(
                            MorningEvent.Rang(t(7, 30)),
                            MorningEvent.Snoozed(t(7, 31), minutes = null, price = PreviewSamples.price(1)),
                            MorningEvent.Stopped(t(8, 10)),
                        ),
                    ),
                ),
            changes = listOf(AlarmChange(LocalTime(6, 15), deleted = true)),
        )

    val items: List<PreviewItem> =
        listOf(
            item("home_paid_two_currencies", "Alarms (Home)", "Paid this week in two currencies", tall = false) { is24 ->
                AppShell(selected = AppTab.Alarms, onSelect = {}) {
                    HomeScreen(state = homePaidTwoCurrencies, is24Hour = is24, onIntent = {})
                }
            },
            progress("progress_money_two_currencies", "Money paid in two currencies, one line each", progressMoneyTwoCurrencies),
            progress("progress_streak_over_30", "Streak over 30: the number and day streak, no / 30", progressStreakOver30),
            day("day_facts_snoozed", "Stored facts only: snoozed twice, paid", factsSnoozed),
            day("day_facts_fallback", "Stored facts only: fallback check, merged alarm", factsFallback),
            day("day_facts_before_unlock", "Stored facts only: before first unlock", factsBeforeUnlock),
            day("day_facts_two_sessions", "Stored facts only: two sessions", factsTwoSessions),
            day("day_facts_missed", "Stored facts only: snoozed once, then missed", factsMissedAfterSnooze),
        )

    private const val YEAR = 2026
    private const val SEPTEMBER = 9

    /** "Today" in the preview, as in round 2. */
    private const val TODAY = 28

    private const val LONG_STREAK = 42

    /** €3.27 and $2.18 this week; €8.98 this month. */
    private const val EUR_WEEK = 3_270_000L
    private const val USD_WEEK = 2_180_000L
    private const val EUR_MONTH = 8_980_000L
}

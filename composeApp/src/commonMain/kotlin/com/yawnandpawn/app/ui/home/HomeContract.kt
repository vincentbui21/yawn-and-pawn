package com.yawnandpawn.app.ui.home

import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.format.Money
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

/** The Home top card: the zero-snooze streak and this week's money. */
data class HomeHero(
    val streakDays: Int,
    /** Paid this week, or `null` for nothing paid ("Nothing paid this week. Keep it that way."). */
    val paidThisWeek: Money?,
)

/** One `card-alarm`. */
data class AlarmCard(
    val id: String,
    val time: LocalTime,
    val repeatDays: Set<DayOfWeek>,
    val label: String?,
    val checks: List<CheckType>,
    val enabled: Boolean,
)

/** "Turn off your {time} alarm? It rings in ..." for an enabled alarm switched off within 8 h (commitment lock). */
data class DisableUnderLock(
    val alarmId: String,
    val time: LocalTime,
    val ringsIn: Countdown,
)

/** What Home (Alarms tab) renders. */
data class HomeUiState(
    val hero: HomeHero? = null,
    /** The next enabled alarm, as a countdown; `null` when none is enabled. */
    val nextAlarm: Countdown? = null,
    val alarms: List<AlarmCard> = emptyList(),
    /** A reliability checklist item fails: the non-dismissible `banner-warning`. */
    val reliabilityProblem: Boolean = false,
    /** A session stopped after 30 minutes with no interaction: the missed note, until dismissed. */
    val missedAlarmAt: LocalTime? = null,
    /** Fallback check used 3 times in 7 days: the info banner suggesting to re-register this check. */
    val reregisterCheck: CheckType? = null,
    /** A session is active: `panel-session-in-progress` replaces everything else. */
    val sessionInProgress: Boolean = false,
    val disableDialog: DisableUnderLock? = null,
)

/** Everything the user can do on Home. */
sealed interface HomeIntent {
    data object AddAlarm : HomeIntent

    data class EditAlarm(
        val id: String,
    ) : HomeIntent

    data class AlarmToggled(
        val id: String,
        val enabled: Boolean,
    ) : HomeIntent

    data object FixSettings : HomeIntent

    data object MissedNoteDismissed : HomeIntent

    data object ReregisterClicked : HomeIntent

    data object ReregisterDismissed : HomeIntent

    data object BackToAlarm : HomeIntent

    data object DisableConfirmed : HomeIntent

    data object DisableCancelled : HomeIntent
}

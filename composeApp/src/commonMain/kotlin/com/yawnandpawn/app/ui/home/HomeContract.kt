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

/** "Delete your {time} alarm? This is logged." for the alarm [alarmId] at [time]. */
data class DeleteAlarmDialog(
    val alarmId: String,
    val time: LocalTime,
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
    /** The session the missed note is about, so "Dismiss" dismisses exactly that one (null in previews). */
    val missedSessionId: String? = null,
    /** Fallback check used 3 times in 7 days: the info banner suggesting to re-register this check. */
    val reregisterCheck: CheckType? = null,
    /** Onboarding's test alarm was skipped: "Ring a test alarm with your phone locked to check it works." until dismissed. */
    val testSkipped: Boolean = false,
    /** A session is active: `panel-session-in-progress` replaces everything else. */
    val sessionInProgress: Boolean = false,
    val disableDialog: DisableUnderLock? = null,
    /** The stored alarms have not been read yet: only the header shows (no empty state, no list). */
    val isLoading: Boolean = false,
    /** The stored alarms could not be read: "Couldn't load your alarms." and "Try again", never the empty state. */
    val loadFailed: Boolean = false,
    /** Delete was chosen for a card: `dialog-confirm` "Delete your {time} alarm? This is logged.". */
    val deleteDialog: DeleteAlarmDialog? = null,
    /** The editor closed because its alarm could not be read: the snackbar "Couldn't open this alarm.". */
    val openFailed: Boolean = false,
    /**
     * A switch or Delete could not be stored: the snackbar "Couldn't save the alarm. Try again." (owner
     * decision 2026-10-02; a switch has already gone back to the stored value).
     */
    val saveFailed: Boolean = false,
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

    /** "Dismiss" on the missed note of [sessionId], the session the note showed. */
    data class MissedNoteDismissed(
        val sessionId: String?,
    ) : HomeIntent

    data object TestNoteDismissed : HomeIntent

    data object ReregisterClicked : HomeIntent

    data object ReregisterDismissed : HomeIntent

    data object BackToAlarm : HomeIntent

    data object DisableConfirmed : HomeIntent

    data object DisableCancelled : HomeIntent

    /** "Duplicate" in a card's long-press menu (or its TalkBack action): opens a new alarm prefilled from it (nothing stored). */
    data class DuplicateClicked(
        val id: String,
    ) : HomeIntent

    /** "Delete" in a card's long-press menu (or its TalkBack action): asks first. */
    data class DeleteClicked(
        val id: String,
    ) : HomeIntent

    data object DeleteConfirmed : HomeIntent

    /** "Keep it", Back or a tap outside the delete dialog. */
    data object DeleteCancelled : HomeIntent

    /** "Try again" after the alarms could not be read. */
    data object RetryLoad : HomeIntent

    /** The editor closed because its alarm could not be read (a navigation result): shows "Couldn't open this alarm.". */
    data object EditorOpenFailed : HomeIntent

    /** Home came back to the foreground: recompute the countdown. */
    data object Resumed : HomeIntent

    /** Home started (`ON_START`, also after returning from a settings screen): check the reliability settings again. */
    data object Started : HomeIntent
}

/** One-shot events for the Home route. */
sealed interface HomeEffect {
    /** Open the editor on the alarm [alarmId], or on a new alarm when `null`. */
    data class OpenEditor(
        val alarmId: String?,
    ) : HomeEffect

    /** Open QR registration of the alarm [alarmId]'s QR/Barcode check, which saves the new code (Re-register, Story 3.10). */
    data class OpenQrRegistration(
        val alarmId: String,
    ) : HomeEffect

    /** Open the editor on a new, unsaved alarm prefilled from the alarm [sourceId] (Duplicate, owner decision 2026-10-05). */
    data class OpenDuplicate(
        val sourceId: String,
    ) : HomeEffect
}

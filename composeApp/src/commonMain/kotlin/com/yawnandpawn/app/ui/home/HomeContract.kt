package com.yawnandpawn.app.ui.home

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.settings.WeakeningNote
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/** The Home top card: the zero-snooze streak and this week's money. */
data class HomeHero(
    val streakDays: Int,
    /**
     * Paid this week, one total per currency (AD-8: never added across currencies; the line joins them with " + ").
     * Empty for nothing paid ("Nothing paid this week. Keep it that way.").
     */
    val paidThisWeek: List<Money> = emptyList(),
)

/** One `card-alarm`. */
data class AlarmCard(
    val id: String,
    val time: LocalTime,
    val repeatDays: Set<DayOfWeek>,
    val label: String?,
    val checks: List<CheckType>,
    val enabled: Boolean,
    /**
     * Its next occurrence is skipped (Story 7.11): "Next ring skipped" replaces the summary line, and the menu and TalkBack
     * offer "Don't skip".
     */
    val skipped: Boolean = false,
)

/**
 * "Skip the {time} alarm?" for the alarm [alarmId] at [time] (Story 7.11, "Skip this one" tapped), with the grey body
 * from [ringsAgain] (owner decision 2026-10-31, 0.1.8): "It rings again tomorrow.", "It rings again on {weekday}." or, a
 * week or more away, "It rings again on {date}."; a one-time alarm (switched off by the skip) has no [ringsAgain] and no
 * body. [occurrence] is the occurrence asked about: "Skip" confirmed once it has passed skips nothing (review fix).
 */
data class SkipDialog(
    val alarmId: String,
    val time: LocalTime,
    val occurrence: Instant? = null,
    val ringsAgain: RingsAgain? = null,
)

/** The ring after the skipped one: on [date], [daysAhead] days after today's local date. */
data class RingsAgain(
    val daysAhead: Int,
    val date: LocalDate,
)

/**
 * "Turn off the {time} alarm?" / "It rings in ..." for an enabled alarm switched off within 8 h (commitment lock, Story
 * 4.6); [ringsIn] is [Countdown.HoursMinutes] (the dialog says the whole hours) or, under 1 h, [Countdown.Minutes].
 */
data class DisableUnderLock(
    val alarmId: String,
    val time: LocalTime,
    val ringsIn: Countdown,
)

/** "Delete the {time} alarm?" for the alarm [alarmId] at [time]. */
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
    /**
     * Onboarding's test alarm was skipped: "Ring a test alarm with your phone locked to check it works." until a locked
     * test passes or it is dismissed (Story 5.12).
     */
    val testSkipped: Boolean = false,
    /** A session is active: `panel-session-in-progress` replaces everything else. */
    val sessionInProgress: Boolean = false,
    val disableDialog: DisableUnderLock? = null,
    /** The stored alarms have not been read yet: only the header shows (no empty state, no list). */
    val isLoading: Boolean = false,
    /** The stored alarms could not be read: "Couldn't load your alarms." and "Try again", never the empty state. */
    val loadFailed: Boolean = false,
    /** Delete was chosen for a card: `dialog-confirm` "Delete the {time} alarm?". */
    val deleteDialog: DeleteAlarmDialog? = null,
    /** The editor closed because its alarm could not be read: the snackbar "Couldn't open this alarm.". */
    val openFailed: Boolean = false,
    /**
     * A switch or Delete could not be stored: the snackbar "Couldn't save the alarm. Try again." (owner
     * decision 2026-10-02; a switch has already gone back to the stored value).
     */
    val saveFailed: Boolean = false,
    /**
     * The editor saved a weakening change under the commitment lock (Story 4.6): the snackbar "Saved. Takes effect after
     * tomorrow's {time} alarm." (or "today's").
     */
    val savedUnderLock: WeakeningNote? = null,
    /** "Skip this one" was tapped on a pre-alarm notification (Story 7.11): `dialog-confirm` "Skip" / "Keep it". */
    val skipDialog: SkipDialog? = null,
    /** The next ring was skipped (Story 7.11): the snackbar "Skipped. No charge.". */
    val skippedNoCharge: Boolean = false,
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

    /** "Fix" on the reliability banner: opens the reliability checklist (Story 5.4). */
    data object FixSettings : HomeIntent

    /** "Dismiss" on the missed note of [sessionId], the session the note showed. */
    data class MissedNoteDismissed(
        val sessionId: String?,
    ) : HomeIntent

    /** "Dismiss" on the skipped-test note (Story 5.12). */
    data object TestNoteDismissed : HomeIntent

    data object ReregisterClicked : HomeIntent

    data object ReregisterDismissed : HomeIntent

    data object BackToAlarm : HomeIntent

    /** "Turn off" in the turn-off dialog: the alarm is switched off (and the commitment event written, Story 4.6). */
    data object DisableConfirmed : HomeIntent

    /** "Keep it on", Back or a tap outside the turn-off dialog: the alarm stays on. */
    data object DisableCancelled : HomeIntent

    /** The editor closed after saving a weakening change under the lock (a navigation result): shows its snackbar. */
    data class SavedUnderLock(
        val note: WeakeningNote,
    ) : HomeIntent

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

    /** A pre-alarm notification's "Skip this one" for the alarm [id] (a navigation request): asks first (Story 7.11). */
    data class SkipRequested(
        val id: String,
    ) : HomeIntent

    /** "Skip" in the skip dialog: the next ring is skipped and logged, free. */
    data object SkipConfirmed : HomeIntent

    /** "Keep it", Back or a tap outside the skip dialog: nothing is skipped. */
    data object SkipCancelled : HomeIntent

    /** "Don't skip" in a skipped card's menu (or its TalkBack action): the skipped ring rings again (Story 7.11). */
    data class DontSkipClicked(
        val id: String,
    ) : HomeIntent

    /** "Try again" after the alarms could not be read. */
    data object RetryLoad : HomeIntent

    /** The editor closed because its alarm could not be read (a navigation result): shows "Couldn't open this alarm.". */
    data object EditorOpenFailed : HomeIntent

    /**
     * Home came back to the foreground (`ON_RESUME`, which also follows every start, e.g. back from the checklist):
     * recompute the countdown and evaluate the reliability checklist again.
     */
    data object Resumed : HomeIntent
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

    /** Open the reliability checklist ("Fix" on the banner, Story 5.4). */
    data object OpenReliability : HomeEffect
}

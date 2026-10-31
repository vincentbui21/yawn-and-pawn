package com.yawnandpawn.app.android.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yawnandpawn.app.RestoreIfIdle
import com.yawnandpawn.app.android.runWithinBudget
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.reminder.SkipReminderNotifier
import com.yawnandpawn.app.core.reminder.SkipReminderTarget
import com.yawnandpawn.app.core.reminder.SkipTodaysAlarms
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.time.Instant

/**
 * The Skip reminder's two actions (0.1.8), from [AndroidSkipReminderNotifier]'s immutable PendingIntents; not exported,
 * and it never starts an activity (the app is not opened):
 * - [ACTION_SKIP] "Skip today's alarms": [SkipTodaysAlarms] for the date in [EXTRA_DATE] (free, a Skipped row each, the
 *   alarms re-armed), inside `goAsync` within the receiver budget. Done, the notification becomes "Today's alarms are
 *   skipped. No charge." until the day's first ring ([EXTRA_FIRST_RING]); once that ring has passed it is removed. During
 *   a session nothing is skipped and the reminder stays; another failure (some alarms may be skipped already) is logged
 *   and the reminder stays too, so a second press skips the rest. Pressed before midnight for the next date, the
 *   confirmation says "Tomorrow's". A process
 *   the broadcast cold-started has no restored session yet (the guard refuses every write then): with nothing stored in
 *   runtime.db it is restored first ([RestoreIfIdle]: Idle, no entry effect, like `ResumePendingDeletion`); a stored
 *   session is left for the wake service, and the skip is refused.
 * - [ACTION_KEEP] "Keep": the notification is removed; nothing else changes.
 *
 * Any other action, or a skip without a readable date, is ignored.
 */
class SkipReminderActionReceiver :
    BroadcastReceiver(),
    KoinComponent {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            ACTION_KEEP -> {
                get<SkipReminderNotifier>().cancel().logged()
            }

            ACTION_SKIP -> {
                val date = intent.getStringExtra(EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
                val firstRing = Instant.fromEpochMilliseconds(intent.getLongExtra(EXTRA_FIRST_RING, 0L))
                runWithinBudget(get(), get(), SKIP) { skip(date, firstRing) }
            }
        }
    }

    private suspend fun skip(
        date: LocalDate,
        firstRing: Instant,
    ) {
        restoreIfIdle()
        when (val skipped = get<SkipTodaysAlarms>()(date)) {
            is Outcome.Success -> {
                val notifier = get<SkipReminderNotifier>()
                val now = get<Clock>().now()
                val left = firstRing - now
                // Pressed the evening before a ring just after midnight: "Tomorrow's alarms are skipped."
                val tomorrow = date != now.toLocalDateTime(get<TimeZoneProvider>().current()).date
                (if (left.isPositive()) notifier.showSkipped(left, tomorrow) else notifier.cancel()).logged()
            }

            is Outcome.Failure -> {
                // A session refused it on purpose: nothing failed, and the reminder stays for after it.
                if (skipped.error != DomainError.SessionActive) get<Logger>().log(LogEvent.OperationFailed.of(SKIP, skipped.error))
            }
        }
    }

    /** A cold start: restores the session engine when runtime.db holds no session, so the guard can let the skip through. */
    private suspend fun restoreIfIdle() {
        if (!get<SessionEngine>().restored.value) get<RestoreIfIdle>()()
    }

    private fun Outcome<Unit, DomainError>.logged() {
        if (this is Outcome.Failure) get<Logger>().log(LogEvent.OperationFailed.of(NOTIFY, error))
    }

    companion object {
        const val ACTION_SKIP = "com.yawnandpawn.app.action.SKIP_TODAYS_ALARMS"
        const val ACTION_KEEP = "com.yawnandpawn.app.action.KEEP_TODAYS_ALARMS"

        /** The local date whose alarms are skipped, as `LocalDate.toString()`. */
        const val EXTRA_DATE = "date"

        /** The date's first ring (epoch millis): the confirmation goes away then. */
        const val EXTRA_FIRST_RING = "firstRing"

        private const val SKIP = "skip today's alarms"
        private const val NOTIFY = "update skip reminder"

        /** "Skip today's alarms" for [target]: an explicit broadcast to this receiver. */
        fun skipIntent(
            context: Context,
            target: SkipReminderTarget,
        ): Intent =
            Intent(context, SkipReminderActionReceiver::class.java)
                .setAction(ACTION_SKIP)
                .putExtra(EXTRA_DATE, target.date.toString())
                .putExtra(EXTRA_FIRST_RING, target.firstRing.toEpochMilliseconds())

        /** "Keep": an explicit broadcast to this receiver. */
        fun keepIntent(context: Context): Intent = Intent(context, SkipReminderActionReceiver::class.java).setAction(ACTION_KEEP)
    }
}

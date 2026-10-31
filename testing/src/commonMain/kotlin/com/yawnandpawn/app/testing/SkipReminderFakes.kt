package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.reminder.SkipReminderNotifier
import com.yawnandpawn.app.core.reminder.SkipReminderPrefs
import com.yawnandpawn.app.core.reminder.SkipReminderStore
import com.yawnandpawn.app.core.reminder.SkipReminderTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate
import kotlin.time.Duration

/**
 * [SkipReminderStore] in memory (0.1.8), starting from [initial]. Set [failRead] to make [read] and [observe] fail (the
 * flow throws, as an unreadable DataStore would), [failWrite] to refuse the writes.
 */
class FakeSkipReminderStore(
    initial: SkipReminderPrefs = SkipReminderPrefs(),
) : SkipReminderStore {
    private val state = MutableStateFlow(initial)

    var failRead: DomainError? = null
    var failWrite: DomainError? = null

    /** What is stored now. */
    var prefs: SkipReminderPrefs
        get() = state.value
        set(value) {
            state.value = value
        }

    override fun observe(): Flow<SkipReminderPrefs> =
        failRead?.let { error -> flow { throw IllegalStateException(error.toString()) } } ?: state

    override suspend fun read(): Outcome<SkipReminderPrefs, DomainError> =
        failRead?.let { Outcome.Failure(it) } ?: Outcome.Success(state.value)

    override suspend fun setEnabled(on: Boolean): Outcome<Unit, DomainError> = write { it.copy(enabled = on) }

    override suspend fun setLastRemindedDate(date: LocalDate): Outcome<Unit, DomainError> = write { it.copy(lastRemindedDate = date) }

    private fun write(change: (SkipReminderPrefs) -> SkipReminderPrefs): Outcome<Unit, DomainError> {
        failWrite?.let { return Outcome.Failure(it) }
        state.update(change)
        return Outcome.Success(Unit)
    }
}

/** What [FakeSkipReminderNotifier] shows. */
sealed interface ShownSkipReminder {
    /** "Up already?" for [target], posted with [ringsIn] left. */
    data class Reminder(
        val target: SkipReminderTarget,
        val ringsIn: Duration,
    ) : ShownSkipReminder

    /** "Today's alarms are skipped. No charge.", gone after [dismissIn]. */
    data class Skipped(
        val dismissIn: Duration,
    ) : ShownSkipReminder
}

/**
 * [SkipReminderNotifier] (0.1.8) that keeps the reminder showing in [showing] (a post replaces it, a cancel removes it),
 * the confirmation in [skipped], and every reminder posted in [posted]. Set [canPost] false to deny the permission,
 * [failPost] to refuse posts.
 */
class FakeSkipReminderNotifier(
    var canPost: Boolean = true,
) : SkipReminderNotifier {
    private val shown = MutableStateFlow<ShownSkipReminder.Reminder?>(null)
    private val confirmation = MutableStateFlow<ShownSkipReminder.Skipped?>(null)
    private val posts = MutableStateFlow<List<ShownSkipReminder.Reminder>>(emptyList())

    var failPost: DomainError? = null

    /** The reminder showing now, or null. */
    val showing: ShownSkipReminder.Reminder?
        get() = shown.value

    /** The confirmation showing now, or null. */
    val skipped: ShownSkipReminder.Skipped?
        get() = confirmation.value

    /** Every reminder posted, oldest first. */
    val posted: List<ShownSkipReminder.Reminder>
        get() = posts.value

    override fun canPost(): Boolean = canPost

    override fun post(
        target: SkipReminderTarget,
        ringsIn: Duration,
        tomorrow: Boolean,
    ): Outcome<Unit, DomainError> {
        failPost?.let { return Outcome.Failure(it) }
        val reminder = ShownSkipReminder.Reminder(target, ringsIn)
        shown.value = reminder
        posts.update { it + reminder }
        return Outcome.Success(Unit)
    }

    override fun showSkipped(
        dismissIn: Duration,
        tomorrow: Boolean,
    ): Outcome<Unit, DomainError> {
        shown.value = null
        confirmation.value = ShownSkipReminder.Skipped(dismissIn)
        return Outcome.Success(Unit)
    }

    override fun cancelSkipped(): Outcome<Unit, DomainError> {
        confirmation.value = null
        return Outcome.Success(Unit)
    }

    override fun cancelLegacy(requestCode: Int): Outcome<Unit, DomainError> = Outcome.Success(Unit)

    override fun cancel(): Outcome<Unit, DomainError> {
        shown.value = null
        confirmation.value = null
        return Outcome.Success(Unit)
    }
}

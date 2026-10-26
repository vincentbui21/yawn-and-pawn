package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.stats.IsoWeek
import com.yawnandpawn.app.core.stats.WeeklySummary
import com.yawnandpawn.app.core.summary.WeeklySummaryNotifier
import com.yawnandpawn.app.core.summary.WeeklySummaryPrefs
import com.yawnandpawn.app.core.summary.WeeklySummaryStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/**
 * [WeeklySummaryStore] in memory (Story 6.8), starting from [initial]. Set [failRead] to make [read] and [observe] fail
 * (the flow throws, as an unreadable DataStore would), [failWrite] to refuse the writes.
 */
class FakeWeeklySummaryStore(
    initial: WeeklySummaryPrefs = WeeklySummaryPrefs(),
) : WeeklySummaryStore {
    private val state = MutableStateFlow(initial)

    var failRead: DomainError? = null
    var failWrite: DomainError? = null

    /** What is stored now. */
    var prefs: WeeklySummaryPrefs
        get() = state.value
        set(value) {
            state.value = value
        }

    override fun observe(): Flow<WeeklySummaryPrefs> =
        failRead?.let { error -> flow { throw IllegalStateException(error.toString()) } } ?: state

    override suspend fun read(): Outcome<WeeklySummaryPrefs, DomainError> =
        failRead?.let { Outcome.Failure(it) } ?: Outcome.Success(state.value)

    override suspend fun setEnabled(on: Boolean): Outcome<Unit, DomainError> = write { it.copy(enabled = on) }

    override suspend fun setLastHandledWeek(week: IsoWeek): Outcome<Unit, DomainError> = write { it.copy(lastHandledWeek = week) }

    private fun write(change: (WeeklySummaryPrefs) -> WeeklySummaryPrefs): Outcome<Unit, DomainError> {
        failWrite?.let { return Outcome.Failure(it) }
        state.update(change)
        return Outcome.Success(Unit)
    }
}

/** [WeeklySummaryNotifier] that keeps every posted summary in [posted]; [allowed] is what [canPost] answers, [failPost] refuses. */
class FakeWeeklySummaryNotifier(
    var allowed: Boolean = true,
) : WeeklySummaryNotifier {
    private val recorded = MutableStateFlow<List<WeeklySummary>>(emptyList())

    var failPost: DomainError? = null

    /** How many times [canPost] was asked. */
    var asked: Int = 0
        private set

    val posted: List<WeeklySummary>
        get() = recorded.value

    override fun canPost(): Boolean {
        asked++
        return allowed
    }

    override fun post(summary: WeeklySummary): Outcome<Unit, DomainError> {
        failPost?.let { return Outcome.Failure(it) }
        recorded.update { it + summary }
        return Outcome.Success(Unit)
    }
}

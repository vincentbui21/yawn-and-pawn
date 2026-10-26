package com.yawnandpawn.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.stats.IsoWeek
import com.yawnandpawn.app.core.summary.WeeklySummaryPrefs
import com.yawnandpawn.app.core.summary.WeeklySummaryStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import okio.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * [WeeklySummaryStore] in the settings DataStore (device-protected and backed up, Story 6.8): the switch under [ENABLED]
 * (absent reads as on) and the last handled ISO week under [LAST_WEEK] ("2027-W01"; absent or unreadable reads as none).
 * "Delete all data" clears the whole file, so the switch reads on again and the week is forgotten.
 *
 * Read errors in [observe] behave as in [DataStoreMissedNoteDismissals]: logged, read as the defaults, then read again
 * after a growing pause. A one-shot [read] that fails, and a write that fails, are a `StorageFailure`.
 */
class DataStoreWeeklySummaryStore(
    private val store: DataStore<Preferences>,
    private val logger: Logger,
) : WeeklySummaryStore {
    override fun observe(): Flow<WeeklySummaryPrefs> =
        store.data
            .retryWhen { cause, attempt ->
                if (cause !is IOException) return@retryWhen false
                logger.log(LogEvent.OperationFailed(READ, cause::class.simpleName.orEmpty()))
                emit(emptyPreferences())
                delay(DataStoreMissedNoteDismissals.readRetryDelay(attempt))
                true
            }.map(::prefsOf)
            .distinctUntilChanged()

    override suspend fun read(): Outcome<WeeklySummaryPrefs, DomainError> = guarded { prefsOf(store.data.first()) }

    override suspend fun setEnabled(on: Boolean): Outcome<Unit, DomainError> = guarded { store.edit { it[ENABLED] = on } }

    override suspend fun setLastHandledWeek(week: IsoWeek): Outcome<Unit, DomainError> =
        guarded { store.edit { it[LAST_WEEK] = week.toString() } }

    // Same boundary as the Room repositories: every storage exception is a StorageFailure, cancellation propagates.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> guarded(block: suspend () -> T): Outcome<T, DomainError> =
        try {
            Outcome.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(DomainError.StorageFailure(e.message ?: e::class.simpleName ?: "storage error"))
        }

    companion object {
        /** The weekly summary switch; absent means on (the default). */
        val ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("weekly_summary_enabled")

        /** The last ISO week a run handled, as `IsoWeek.toString()`. */
        val LAST_WEEK: Preferences.Key<String> = stringPreferencesKey("weekly_summary_last_week")

        private const val READ = "read weekly summary"

        private fun prefsOf(preferences: Preferences): WeeklySummaryPrefs =
            WeeklySummaryPrefs(
                enabled = preferences[ENABLED] ?: true,
                lastHandledWeek = preferences[LAST_WEEK]?.let(IsoWeek::parse),
            )
    }
}

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
import com.yawnandpawn.app.core.reminder.SkipReminderPrefs
import com.yawnandpawn.app.core.reminder.SkipReminderStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.datetime.LocalDate
import okio.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * [SkipReminderStore] in the settings DataStore (device-protected and backed up, 0.1.8): the switch under [ENABLED]
 * (absent reads as on) and the last reminded local date under [LAST_DATE] ("2027-03-04"; absent or unreadable reads as
 * none). "Delete all data" clears the whole file, so the switch reads on again and the date is forgotten.
 *
 * Read errors in [observe] behave as in [DataStoreMissedNoteDismissals]: logged, read as the defaults, then read again
 * after a growing pause. A one-shot [read] that fails, and a write that fails, are a `StorageFailure`.
 */
class DataStoreSkipReminderStore(
    private val store: DataStore<Preferences>,
    private val logger: Logger,
) : SkipReminderStore {
    override fun observe(): Flow<SkipReminderPrefs> =
        store.data
            .retryWhen { cause, attempt ->
                if (cause !is IOException) return@retryWhen false
                logger.log(LogEvent.OperationFailed(READ, cause::class.simpleName.orEmpty()))
                emit(emptyPreferences())
                delay(DataStoreMissedNoteDismissals.readRetryDelay(attempt))
                true
            }.map(::prefsOf)
            .distinctUntilChanged()

    override suspend fun read(): Outcome<SkipReminderPrefs, DomainError> = guarded { prefsOf(store.data.first()) }

    override suspend fun setEnabled(on: Boolean): Outcome<Unit, DomainError> = guarded { store.edit { it[ENABLED] = on } }

    override suspend fun setLastRemindedDate(date: LocalDate): Outcome<Unit, DomainError> =
        guarded { store.edit { it[LAST_DATE] = date.toString() } }

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
        /** The Skip reminder switch; absent means on (the default). */
        val ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("skip_reminder_enabled")

        /** The last local date a reminder was handled for, as `LocalDate.toString()`. */
        val LAST_DATE: Preferences.Key<String> = stringPreferencesKey("skip_reminder_last_date")

        private const val READ = "read skip reminder"

        private fun prefsOf(preferences: Preferences): SkipReminderPrefs =
            SkipReminderPrefs(
                enabled = preferences[ENABLED] ?: true,
                lastRemindedDate = preferences[LAST_DATE]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            )
    }
}

package com.yawnandpawn.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.MissedNoteDismissals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException

/**
 * [MissedNoteDismissals] in the settings DataStore (device-protected, Story 1.16): the dismissed session ids under
 * [KEY]. A read failure emits an empty set (the note shows again rather than never); a write failure is a
 * `StorageFailure`.
 */
class DataStoreMissedNoteDismissals(
    private val store: DataStore<Preferences>,
) : MissedNoteDismissals {
    override fun dismissed(): Flow<Set<String>> =
        store.data
            .map { it[KEY].orEmpty() }
            .catch { emit(emptySet()) }

    // Same boundary as the Room repositories: every storage exception is a StorageFailure, cancellation propagates.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun dismiss(sessionId: String): Outcome<Unit, DomainError> =
        try {
            store.edit { it[KEY] = it[KEY].orEmpty() + sessionId }
            Outcome.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(DomainError.StorageFailure(e.message ?: e::class.simpleName ?: "storage error"))
        }

    companion object {
        /** The dismissed session ids. */
        val KEY: Preferences.Key<Set<String>> = stringSetPreferencesKey("missed_note_dismissed_sessions")
    }
}

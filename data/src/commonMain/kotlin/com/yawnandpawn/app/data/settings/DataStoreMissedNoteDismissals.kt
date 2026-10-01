package com.yawnandpawn.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.MissedNoteDismissals
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import okio.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * [MissedNoteDismissals] in the settings DataStore (device-protected, Story 1.16): the dismissed session ids under
 * [KEY]. A file read error is logged and reads as nothing dismissed (the note shows again rather than never); the flow
 * then reads the file again after a growing pause, so it stays alive and a later dismissal still reaches Home. Other
 * errors are not hidden. A write failure is a `StorageFailure`.
 */
class DataStoreMissedNoteDismissals(
    private val store: DataStore<Preferences>,
    private val logger: Logger,
) : MissedNoteDismissals {
    override fun dismissed(): Flow<Set<String>> =
        store.data
            .retryWhen { cause, attempt ->
                if (cause !is IOException) return@retryWhen false
                logger.log(LogEvent.OperationFailed("read missed note dismissals", cause::class.simpleName.orEmpty()))
                emit(emptyPreferences())
                delay(readRetryDelay(attempt))
                true
            }.map { it[KEY].orEmpty() }

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

        private val FIRST_RETRY: Duration = 1.seconds
        private val MAX_RETRY: Duration = 1.minutes
        private const val MAX_DOUBLINGS = 6L

        /** The pause after failed read [attempt] (0 first): 1 s, doubling, at most a minute. */
        fun readRetryDelay(attempt: Long): Duration {
            val doublings = attempt.coerceIn(0L, MAX_DOUBLINGS).toInt()
            return (FIRST_RETRY * (1 shl doublings)).coerceAtMost(MAX_RETRY)
        }
    }
}

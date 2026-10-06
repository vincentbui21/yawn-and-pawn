package com.yawnandpawn.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.stats.CheckKey
import com.yawnandpawn.app.core.stats.ReRegisterDismissals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import okio.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * [ReRegisterDismissals] in the settings DataStore (device-protected, Story 3.13): one epoch-millis entry per check,
 * keyed `reregister_dismissed/<alarm id>/<check type id>`; a later dismissal of the same check replaces it. Read errors
 * behave as in [DataStoreMissedNoteDismissals]: logged, read as nothing dismissed (the banner shows again rather than
 * never), then read again after a growing pause. A write failure is a `StorageFailure`.
 */
class DataStoreReRegisterDismissals(
    private val store: DataStore<Preferences>,
    private val logger: Logger,
) : ReRegisterDismissals {
    override fun dismissed(): Flow<Map<CheckKey, Instant>> =
        store.data
            .retryWhen { cause, attempt ->
                if (cause !is IOException) return@retryWhen false
                logger.log(LogEvent.OperationFailed("read re-register dismissals", cause::class.simpleName.orEmpty()))
                emit(emptyPreferences())
                delay(DataStoreMissedNoteDismissals.readRetryDelay(attempt))
                true
            }.map(::dismissals)

    // Same boundary as the Room repositories: every storage exception is a StorageFailure, cancellation propagates.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun dismiss(
        key: CheckKey,
        at: Instant,
    ): Outcome<Unit, DomainError> =
        try {
            store.edit { it[keyOf(key)] = at.toEpochMilliseconds() }
            Outcome.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(DomainError.StorageFailure(e.message ?: e::class.simpleName ?: "storage error"))
        }

    companion object {
        /** Prefix of every entry; the rest is `<alarm id>/<check type id>` (type ids have no `/`). */
        const val PREFIX = "reregister_dismissed/"

        fun keyOf(check: CheckKey): Preferences.Key<Long> = longPreferencesKey("$PREFIX${check.alarmId}/${check.typeId}")

        private fun dismissals(preferences: Preferences): Map<CheckKey, Instant> =
            preferences
                .asMap()
                .mapNotNull { (key, value) ->
                    val rest = key.name.removePrefix(PREFIX)
                    val split = rest.lastIndexOf('/')
                    if (rest == key.name || split <= 0 || value !is Long) return@mapNotNull null
                    CheckKey(rest.substring(0, split), rest.substring(split + 1)) to Instant.fromEpochMilliseconds(value)
                }.toMap()
    }
}

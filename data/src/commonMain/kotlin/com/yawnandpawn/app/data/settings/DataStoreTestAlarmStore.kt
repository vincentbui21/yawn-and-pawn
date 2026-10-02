package com.yawnandpawn.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.SessionConfig
import com.yawnandpawn.app.core.session.SessionJson
import com.yawnandpawn.app.core.session.TestAlarmStore
import kotlin.coroutines.cancellation.CancellationException

/**
 * [TestAlarmStore] in the settings DataStore (device-protected, Story 1.18): the one pending test ring under [KEY], as
 * the pinned session JSON. [take] reads and removes it in one edit, so a fire never rings the same test twice. A value
 * that cannot be decoded is logged (never its text: it holds the label), dropped and read as none. Storage errors are a
 * `StorageFailure`.
 */
class DataStoreTestAlarmStore(
    private val store: DataStore<Preferences>,
    private val logger: Logger,
) : TestAlarmStore {
    override suspend fun put(config: SessionConfig): Outcome<Unit, DomainError> =
        storage { store.edit { it[KEY] = SessionJson.encodeConfig(config) } }

    override suspend fun take(): Outcome<SessionConfig?, DomainError> =
        storage {
            var text: String? = null
            store.edit { preferences ->
                text = preferences[KEY]
                preferences.remove(KEY)
            }
            text?.let { stored ->
                SessionJson.decodeConfig(stored).also { config ->
                    if (config == null) logger.log(LogEvent.OperationFailed("read pending test alarm", "undecodable"))
                }
            }
        }

    // Same boundary as the Room repositories: every storage exception is a StorageFailure, cancellation propagates.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> storage(block: suspend () -> T): Outcome<T, DomainError> =
        try {
            Outcome.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(DomainError.StorageFailure(e.message ?: e::class.simpleName ?: "storage error"))
        }

    companion object {
        /** The pending test ring's config. */
        val KEY: Preferences.Key<String> = stringPreferencesKey("pending_test_alarm")
    }
}

package com.yawnandpawn.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yawnandpawn.app.core.billing.InstallIdProvider
import com.yawnandpawn.app.core.billing.InstallIdReset
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import kotlin.coroutines.cancellation.CancellationException

/**
 * [InstallIdProvider] over the install id's own DataStore (Story 4.8): the first call stores a new UUID v4 from [ids]
 * (random, never from a device or account identifier) in one atomic edit, so two first calls agree; every later call,
 * also in a new process, returns the same one. A failure is logged by operation and error type only: the id itself is
 * never logged. [resetInstallId] ("Delete all data", Story 5.9) removes the stored id, so the next call makes a new one;
 * nothing caches it, so no old id is handed out after a reset.
 */
class DataStoreInstallIdProvider(
    private val store: DataStore<Preferences>,
    private val ids: IdGenerator,
    private val logger: Logger,
) : InstallIdProvider,
    InstallIdReset {
    // Every storage exception is a StorageFailure, cancellation propagates; the log names only the exception type.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun installId(): Outcome<String, DomainError> =
        try {
            var id = ""
            store.edit { preferences ->
                val stored = preferences[KEY]?.takeIf { it.isNotBlank() }
                id = stored ?: ids.newId().also { preferences[KEY] = it }
            }
            Outcome.Success(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val type = e::class.simpleName ?: "storage error"
            logger.log(LogEvent.OperationFailed("read install id", type))
            Outcome.Failure(DomainError.StorageFailure(type))
        }

    // Same boundary as installId(): a storage exception is a StorageFailure, cancellation propagates.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun resetInstallId(): Outcome<Unit, DomainError> =
        try {
            store.edit { it.remove(KEY) }
            Outcome.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val type = e::class.simpleName ?: "storage error"
            logger.log(LogEvent.OperationFailed("reset install id", type))
            Outcome.Failure(DomainError.StorageFailure(type))
        }

    companion object {
        val KEY: Preferences.Key<String> = stringPreferencesKey("install_id")
    }
}

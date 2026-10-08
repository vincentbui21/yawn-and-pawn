package com.yawnandpawn.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yawnandpawn.app.core.billing.PriceCacheStore
import com.yawnandpawn.app.core.billing.PriceCatalogJson
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException

/**
 * [PriceCacheStore] in the price cache DataStore (Story 4.3): the whole snapshot as one [PriceCatalogJson] string
 * under [KEY], so an update replaces it atomically (one DataStore edit reads, transforms and writes).
 * - A value that cannot be decoded is logged (never its text) and read as empty; the next update overwrites it.
 * - A store that cannot be read emits [PriceCatalogSnapshot.EMPTY] (logged) instead of failing the collector, so a
 *   screen waiting for prices shows "not loaded" rather than crashing.
 * - Update failures are a `StorageFailure`; nothing changed then.
 */
class DataStorePriceCacheStore(
    private val store: DataStore<Preferences>,
    private val logger: Logger,
) : PriceCacheStore {
    override fun observe(): Flow<PriceCatalogSnapshot> =
        store.data
            .map { decode(it) }
            .catch { e ->
                if (e is CancellationException) throw e
                logger.log(LogEvent.OperationFailed(READ, e::class.simpleName ?: "read error"))
                emit(PriceCatalogSnapshot.EMPTY)
            }.distinctUntilChanged()

    // Same boundary as the other DataStore adapters: every storage exception is a StorageFailure, cancellation propagates.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun update(transform: (PriceCatalogSnapshot) -> PriceCatalogSnapshot): Outcome<PriceCatalogSnapshot, DomainError> =
        try {
            var written = PriceCatalogSnapshot.EMPTY
            store.edit { preferences ->
                written = transform(decode(preferences))
                preferences[KEY] = PriceCatalogJson.encode(written)
            }
            Outcome.Success(written)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(DomainError.StorageFailure(e.message ?: e::class.simpleName ?: "storage error"))
        }

    private fun decode(preferences: Preferences): PriceCatalogSnapshot {
        val text = preferences[KEY] ?: return PriceCatalogSnapshot.EMPTY
        return PriceCatalogJson.decode(text) ?: PriceCatalogSnapshot.EMPTY.also {
            logger.log(LogEvent.OperationFailed(READ, "undecodable"))
        }
    }

    companion object {
        /** The snapshot, as [PriceCatalogJson]. */
        val KEY: Preferences.Key<String> = stringPreferencesKey("price_catalog")

        private const val READ = "read price cache"
    }
}

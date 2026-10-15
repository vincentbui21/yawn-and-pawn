package com.yawnandpawn.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.reliability.ChecklistRow
import com.yawnandpawn.app.core.reliability.MakerConfirmation
import com.yawnandpawn.app.core.reliability.ReliabilityRecords
import com.yawnandpawn.app.core.reliability.StoredReliability
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * [ReliabilityRecords] in the settings DataStore (device-protected, AD-6, Story 5.3): the rows seen OK under
 * [LAST_OK] (row names; a name this version does not know is skipped), when a locked test passed under
 * [LOCKED_TEST_PASSED_AT] (epoch millis) and the maker confirmation under [MAKER] and [FINGERPRINT] (both or none).
 * Every storage error, a read included, is a `StorageFailure`; `EvaluateReliability` then writes nothing.
 */
class DataStoreReliabilityRecords(
    private val store: DataStore<Preferences>,
) : ReliabilityRecords {
    override suspend fun read(): Outcome<StoredReliability, DomainError> = storage { stored(store.data.first()) }

    override suspend fun saveLastOk(rows: Set<ChecklistRow>): Outcome<Unit, DomainError> =
        edit { preferences -> preferences[LAST_OK] = rows.map { it.name }.toSet() }

    override suspend fun lockedTestPassed(at: Instant): Outcome<Unit, DomainError> =
        edit { it[LOCKED_TEST_PASSED_AT] = at.toEpochMilliseconds() }

    override suspend fun confirmMaker(confirmation: MakerConfirmation): Outcome<Unit, DomainError> =
        edit {
            it[MAKER] = confirmation.maker
            it[FINGERPRINT] = confirmation.fingerprint
        }

    private suspend fun edit(change: (MutablePreferences) -> Unit): Outcome<Unit, DomainError> =
        storage {
            store.edit { change(it) }
            Unit
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
        /** The rows seen OK, by [ChecklistRow] name. */
        val LAST_OK: Preferences.Key<Set<String>> = stringSetPreferencesKey("reliability_last_ok")

        /** When a test rung with the phone locked was completed, in epoch millis. */
        val LOCKED_TEST_PASSED_AT: Preferences.Key<Long> = longPreferencesKey("locked_test_passed_at")

        /** The maker of the confirmed guidance. */
        val MAKER: Preferences.Key<String> = stringPreferencesKey("maker_confirmed_maker")

        /** The build fingerprint of the confirmed guidance. */
        val FINGERPRINT: Preferences.Key<String> = stringPreferencesKey("maker_confirmed_fingerprint")

        private fun stored(preferences: Preferences): StoredReliability {
            val maker = preferences[MAKER]
            val fingerprint = preferences[FINGERPRINT]
            return StoredReliability(
                lastOk = preferences[LAST_OK].orEmpty().mapNotNull { name -> ChecklistRow.entries.firstOrNull { it.name == name } }.toSet(),
                lockedTestPassedAt = preferences[LOCKED_TEST_PASSED_AT]?.let(Instant::fromEpochMilliseconds),
                makerConfirmation = if (maker != null && fingerprint != null) MakerConfirmation(maker, fingerprint) else null,
            )
        }
    }
}

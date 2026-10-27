package com.yawnandpawn.app.data.reset

import androidx.datastore.preferences.core.edit
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.reset.LocalDataEraser
import com.yawnandpawn.app.data.config.SharedPreferencesSettingsCache
import com.yawnandpawn.app.data.recording.RecordingFiles
import com.yawnandpawn.app.data.session.PurchaseIntentDao
import com.yawnandpawn.app.data.settings.ConsentDataStore
import com.yawnandpawn.app.data.settings.PriceCacheDataStore
import com.yawnandpawn.app.data.settings.ReliabilityDataStore
import com.yawnandpawn.app.data.settings.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * [LocalDataEraser] over the app's own files (Story 5.9): the `recordings/` folder with every audio file (Story 7.1;
 * a `StorageFailure` before the first unlock) and then the alarm tables of `app.db` ([AlarmDataDao], with the
 * `recording` rows), the purchase
 * intents of `runtime.db`, the whole settings DataStore (`clear()`, so every key, also the ones later stories add), the
 * settings fallback preferences (theme and defaults too, once Story 5.1 caches them), the price cache and the
 * reliability checklist's records (`datastore/reliability.preferences_pb`, Story 5.3: the locked test, the rows seen OK
 * and the maker confirmation, so a fresh start runs the checklist and the locked test again) and the usage stats choice
 * (`datastore/usage_stats_consent.preferences_pb`, Story 5.6: off and not asked, so onboarding asks again). The per-device
 * preferences (`wake_runtime`, the `reliability` notification-asked flag, `backup_restore`) and the install id file are
 * not touched here. Storage exceptions become `StorageFailure`.
 */
class AndroidLocalDataEraser(
    private val alarmData: AlarmDataDao,
    private val intents: PurchaseIntentDao,
    private val settings: SettingsDataStore,
    private val settingsCache: SharedPreferencesSettingsCache,
    private val prices: PriceCacheDataStore,
    private val reliability: ReliabilityDataStore,
    private val consent: ConsentDataStore,
    private val recordings: RecordingFiles,
) : LocalDataEraser {
    override suspend fun deleteAlarmData(): Outcome<Unit, DomainError> =
        storage {
            // The audio first: before the first unlock it cannot be seen, so this fails (and the deletion retries later)
            // before any row goes; rows deleted first would leave the files with nothing pointing at them.
            withContext(Dispatchers.IO) { recordings.deleteAll() }
            alarmData.deleteAllAlarmData()
        }

    override suspend fun deletePurchaseIntents(keepSessions: Set<String>): Outcome<Unit, DomainError> =
        storage { intents.deleteAllExcept(keepSessions.toList()) }

    override suspend fun clearSettings(): Outcome<Unit, DomainError> =
        storage {
            settings.clear()
            check(settingsCache.clear()) { "settings fallback not cleared" }
            prices.store.edit { it.clear() }
            reliability.store.edit { it.clear() }
            consent.store.edit { it.clear() }
        }

    // Same boundary as the Room and DataStore adapters: every storage exception is a StorageFailure, cancellation propagates.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun storage(block: suspend () -> Unit): Outcome<Unit, DomainError> =
        try {
            block()
            Outcome.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(DomainError.StorageFailure(e.message ?: e::class.simpleName ?: "storage error"))
        }
}

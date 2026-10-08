package com.yawnandpawn.app.data.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yawnandpawn.app.core.config.GlobalSettingsRepository
import com.yawnandpawn.app.core.config.LockedField
import com.yawnandpawn.app.core.config.PendingChange
import com.yawnandpawn.app.core.config.PendingChangeJson
import com.yawnandpawn.app.core.config.PendingChangeRepository
import com.yawnandpawn.app.core.config.SaveGlobalSetting
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.flatMap
import com.yawnandpawn.app.core.error.map
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.data.settings.DataStoreMissedNoteDismissals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import okio.IOException

/**
 * The global settings (FR-SET-1) and the global pending changes (Story 4.4) in the settings DataStore (device-protected,
 * so a ring before the first unlock reads them; backed up). AD-6: no copy in `app.db`.
 * - [BASE_FEE_TIER] and [MAX_SNOOZES]: a missing or out-of-range value reads as the `GlobalSettings` default.
 * - [PENDING_GLOBAL]: the global pending changes as one `PendingChangeJson` list; a list that does not decode reads as
 *   none (the live settings, the stronger ones, apply).
 *
 * Read errors in the flows behave as in [DataStoreMissedNoteDismissals]: logged, read as the defaults, then read again
 * after a growing pause. A failed one-shot read or write is a `StorageFailure`.
 */
class DataStoreGlobalSettings(
    private val store: DataStore<Preferences>,
    private val logger: Logger,
) : GlobalSettingsRepository {
    override fun observe(): Flow<GlobalSettings> = preferences("read global settings").map(::settingsOf)

    override suspend fun get(): Outcome<GlobalSettings, DomainError> = storage { settingsOf(store.data.first()) }

    override suspend fun setBaseFeeTier(tier: Int): Outcome<Unit, DomainError> = storage { store.edit { it[BASE_FEE_TIER] = tier } }

    override suspend fun setMaxSnoozes(count: Int): Outcome<Unit, DomainError> = storage { store.edit { it[MAX_SNOOZES] = count } }

    /** The global pending changes; emits again after each change. */
    fun observePending(): Flow<List<PendingChange>> = preferences("read global pending changes").map(::pendingOf)

    suspend fun allPending(): Outcome<List<PendingChange>, DomainError> = storage { pendingOf(store.data.first()) }

    /** Stores the global [change], replacing the one of the same field, in one edit. */
    suspend fun putPending(change: PendingChange): Outcome<Unit, DomainError> =
        storage {
            store.edit { preferences ->
                preferences[PENDING_GLOBAL] =
                    PendingChangeJson.encodeGlobal(pendingOf(preferences).filterNot { it.field == change.field } + change)
            }
        }

    suspend fun removePending(field: LockedField): Outcome<Unit, DomainError> =
        storage {
            store.edit { preferences ->
                val left = pendingOf(preferences).filterNot { it.field == field }
                if (left.isEmpty()) {
                    preferences.remove(PENDING_GLOBAL)
                } else {
                    preferences[PENDING_GLOBAL] =
                        PendingChangeJson.encodeGlobal(left)
                }
            }
        }

    private fun preferences(operation: String): Flow<Preferences> =
        store.data.retryWhen { cause, attempt ->
            if (cause !is IOException) return@retryWhen false
            logger.log(LogEvent.OperationFailed(operation, cause::class.simpleName.orEmpty()))
            emit(emptyPreferences())
            delay(DataStoreMissedNoteDismissals.readRetryDelay(attempt))
            true
        }

    companion object {
        /** The base fee tier, 1–10. */
        val BASE_FEE_TIER: Preferences.Key<Int> = intPreferencesKey("base_fee_tier")

        /** Max snoozes per session, 1–5. */
        val MAX_SNOOZES: Preferences.Key<Int> = intPreferencesKey("max_snoozes")

        /** The global pending changes (`PendingChangeJson.encodeGlobal`). */
        val PENDING_GLOBAL: Preferences.Key<String> = stringPreferencesKey("pending_global_changes")

        private fun settingsOf(preferences: Preferences): GlobalSettings {
            val defaults = GlobalSettings()
            return defaults.copy(
                baseFeeTier = preferences[BASE_FEE_TIER]?.takeIf { it in SaveGlobalSetting.BASE_FEE_TIERS } ?: defaults.baseFeeTier,
                maxSnoozes = preferences[MAX_SNOOZES]?.takeIf { it in SaveGlobalSetting.MAX_SNOOZES } ?: defaults.maxSnoozes,
            )
        }

        private fun pendingOf(preferences: Preferences): List<PendingChange> =
            preferences[PENDING_GLOBAL]?.let(PendingChangeJson::decodeGlobal).orEmpty().filter { it.field.global }
    }
}

/**
 * [PendingChangeRepository] over both stores (AD-6): an alarm's changes in `app.db` ([alarms]), the global ones in the
 * settings DataStore ([global]).
 */
class CompositePendingChangeRepository(
    private val alarms: RoomPendingChangeRepository,
    private val global: DataStoreGlobalSettings,
) : PendingChangeRepository {
    override fun observe(): Flow<List<PendingChange>> = combine(global.observePending(), alarms.observe()) { g, a -> g + a }

    override suspend fun all(): Outcome<List<PendingChange>, DomainError> = global.allPending().flatMap { g -> alarms.all().map { g + it } }

    override suspend fun put(change: PendingChange): Outcome<Unit, DomainError> =
        if (change.alarmId == null) global.putPending(change) else alarms.put(change)

    override suspend fun remove(
        alarmId: String?,
        field: LockedField,
    ): Outcome<Unit, DomainError> = if (alarmId == null) global.removePending(field) else alarms.remove(alarmId, field)
}

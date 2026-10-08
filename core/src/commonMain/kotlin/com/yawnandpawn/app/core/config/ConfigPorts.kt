package com.yawnandpawn.app.core.config

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.GlobalSettings
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * Port for the pending changes (AD-16, `FakePendingChangeRepository` in tests). An alarm's changes live in `app.db`
 * (`pending_change`, deleted with their alarm), the global ones in the settings DataStore (AD-6: no settings copy in
 * `app.db`). At most one change per (alarm or global, field). Storage errors are `StorageFailure`, never thrown; a stored
 * value this build cannot decode is skipped.
 */
interface PendingChangeRepository {
    /** Every pending change, global and per alarm; emits again after each change (Stories 4.5 and 4.6 show them). */
    fun observe(): Flow<List<PendingChange>>

    /** Every pending change, global and per alarm. */
    suspend fun all(): Outcome<List<PendingChange>, DomainError>

    /** The pending changes of the alarm [alarmId] only (the global ones come with the settings, [SettingsSnapshot]). */
    suspend fun forAlarm(alarmId: String): Outcome<List<PendingChange>, DomainError>

    /** Stores [change], replacing the one of the same alarm (or global) and field. */
    suspend fun put(change: PendingChange): Outcome<Unit, DomainError>

    /** Removes the pending change of [alarmId] (null: global) and [field]; nothing to remove is a success. */
    suspend fun remove(
        alarmId: String?,
        field: LockedField,
    ): Outcome<Unit, DomainError>
}

/**
 * Port for the persisted global settings (FR-SET-1) in the settings DataStore (device-protected, backed up). Only the
 * base fee tier and max snoozes are stored so far; the other [GlobalSettings] fields keep their defaults (Epic 5). A
 * stored value outside its range reads as the default.
 */
interface GlobalSettingsRepository {
    /** The live settings; emits again after each change. */
    fun observe(): Flow<GlobalSettings>

    suspend fun get(): Outcome<GlobalSettings, DomainError>

    suspend fun setBaseFeeTier(tier: Int): Outcome<Unit, DomainError>

    suspend fun setMaxSnoozes(count: Int): Outcome<Unit, DomainError>

    /** The live settings and the global pending changes from one read of the store, so the two always agree (the fire). */
    suspend fun snapshot(): Outcome<SettingsSnapshot, DomainError>

    /**
     * The last snapshot this app read or wrote successfully, kept across process deaths ([SettingsSnapshotCache]); null
     * when there was none. The fire rings with it when the store cannot be read in time, rather than with the weakest
     * defaults (review fix 11).
     */
    fun lastKnown(): SettingsSnapshot?
}

/** The global settings and the global pending changes as one read of the settings store saw them. */
data class SettingsSnapshot(
    val settings: GlobalSettings,
    val pending: List<PendingChange>,
)

/**
 * Port for the last-known [SettingsSnapshot] (device-protected, not backed up: a per-device fallback). Synchronous and
 * small, so the fire can read it at once; never throws (a failure reads as none and a failed save is dropped).
 */
interface SettingsSnapshotCache {
    fun load(): SettingsSnapshot?

    fun save(snapshot: SettingsSnapshot)
}

/** What the user did to an alarm inside its lock window (PRD §6.2: allowed, confirmed and logged). The names are stored. */
enum class CommitmentAction {
    Disabled,
    Deleted,
}

/**
 * The user turned off or deleted [alarmId] at [at], [occurrenceAt] being the ring inside the lock window it gave up (read
 * by Day detail in Epic 6; PRD Q15 stays open for how it shows).
 */
data class CommitmentEvent(
    val id: String,
    val alarmId: String,
    val occurrenceAt: Instant,
    val action: CommitmentAction,
    val at: Instant,
)

/** Port for the commitment events (`commitment_event` in `app.db`, kept after the alarm is deleted). */
interface CommitmentEventRepository {
    suspend fun insert(event: CommitmentEvent): Outcome<Unit, DomainError>

    /** Every event, oldest first. */
    suspend fun all(): Outcome<List<CommitmentEvent>, DomainError>
}

/** Promotes the pending changes that are due ([PromotePendingChanges]); `AlarmScheduling.rescheduleAll` calls it first. */
fun interface PendingChangePromotion {
    suspend fun promote()
}

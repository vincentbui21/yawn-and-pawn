package com.yawnandpawn.app.core.config

import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.SessionReducer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The settings the commitment lock covers (PRD §6.2 lists only fee, checks, grace and snoozes): [BaseFee] and
 * [MaxSnoozes] are global, [GraceSeconds] and [Checks] belong to an alarm. Snooze length, sound, volume, label and repeat
 * days are never locked. The names are stored (`pending_change.field`), so they never change.
 */
enum class LockedField(
    val global: Boolean,
) {
    BaseFee(global = true),
    MaxSnoozes(global = true),
    GraceSeconds(global = false),
    Checks(global = false),
}

/** A value of one [LockedField], stored as JSON ([PendingChangeJson]); the serial names never change. */
@Serializable
sealed interface SettingValue {
    val field: LockedField

    /** The base fee as a `FeeLadder` tier, 1–10. */
    @Serializable
    @SerialName("BaseFee")
    data class BaseFeeTier(
        val tier: Int,
    ) : SettingValue {
        override val field: LockedField get() = LockedField.BaseFee
    }

    /** Max snoozes per session, 1–5. */
    @Serializable
    @SerialName("MaxSnoozes")
    data class MaxSnoozes(
        val count: Int,
    ) : SettingValue {
        override val field: LockedField get() = LockedField.MaxSnoozes
    }

    /** An alarm's grace window ("quiet time"), in seconds. */
    @Serializable
    @SerialName("GraceSeconds")
    data class GraceSeconds(
        val seconds: Int,
    ) : SettingValue {
        override val field: LockedField get() = LockedField.GraceSeconds
    }

    /** An alarm's checks: its check mode and its entries in order. */
    @Serializable
    @SerialName("Checks")
    data class Checks(
        val plan: CheckPlan,
    ) : SettingValue {
        override val field: LockedField get() = LockedField.Checks
    }
}

/**
 * A weakening change made inside the lock window (AD-16): [value] becomes the live setting only after
 * [effectiveAfter]. [alarmId] is the alarm whose setting it is, or null for a global setting. There is at most one per
 * (alarm, field); a newer one replaces it.
 */
data class PendingChange(
    val alarmId: String?,
    val value: SettingValue,
    val effectiveAfter: Occurrence,
) {
    val field: LockedField get() = value.field

    /** Applies to a session ringing at [scheduledAt]: only an occurrence strictly after the one it waits for. */
    fun appliesTo(scheduledAt: Instant): Boolean = scheduledAt > effectiveAfter.scheduledAt

    /**
     * The occurrence it waits for is over at [now], so this is the effective value (due to be promoted): more than
     * [SETTLE] after it. Not right after it: the fire that rings it may still be on its way to `AlarmFired` (a cold start
     * promotes on app start, review fix), and promoting then would ring the protected occurrence with the weaker value.
     * A session still in progress after [SETTLE] (snoozed) is skipped by [PromotePendingChanges] itself.
     */
    fun isDue(now: Instant): Boolean = now > effectiveAfter.scheduledAt + SETTLE

    companion object {
        /** The 30-minute no-interaction timeout (FR-ALM-9) and a minute's slack: a ring is over or in progress by then. */
        val SETTLE: Duration = SessionReducer.NO_INTERACTION_TIMEOUT + 1.minutes
    }
}

/**
 * The one JSON format of a stored [SettingValue] (`pending_change.value_json` and the settings DataStore). Unknown keys
 * are ignored, the variant name sits in `type` and defaults are written, as in `SessionJson`.
 */
object PendingChangeJson {
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            encodeDefaults = true
        }

    fun encode(value: SettingValue): String = json.encodeToString(SettingValue.serializer(), value)

    /** The value stored as [text], or null when it cannot be decoded (a newer or damaged row). */
    fun decode(text: String): SettingValue? = runCatching { json.decodeFromString(SettingValue.serializer(), text) }.getOrNull()

    /** A global pending change as stored in the settings DataStore. */
    @Serializable
    private data class StoredGlobal(
        val value: SettingValue,
        val alarmId: String,
        val scheduledAtMillis: Long,
    )

    /** The global [changes] as one JSON list. */
    fun encodeGlobal(changes: List<PendingChange>): String =
        json.encodeToString(
            ListSerializer(StoredGlobal.serializer()),
            changes.map { it.toStored() },
        )

    /** The global changes stored as [text]; a list that cannot be decoded reads as none. */
    fun decodeGlobal(text: String): List<PendingChange> =
        runCatching { json.decodeFromString(ListSerializer(StoredGlobal.serializer()), text).map { it.toChange() } }
            .getOrDefault(emptyList())

    private fun StoredGlobal.toChange() = PendingChange(null, value, Occurrence(alarmId, Instant.fromEpochMilliseconds(scheduledAtMillis)))

    private fun PendingChange.toStored() = StoredGlobal(value, effectiveAfter.alarmId, effectiveAfter.scheduledAt.toEpochMilliseconds())

    /** A [SettingsSnapshot] as the fire's fallback cache keeps it. */
    @Serializable
    private data class StoredSnapshot(
        val baseFeeTier: Int,
        val maxSnoozes: Int,
        val pending: List<StoredGlobal>,
    )

    /** [snapshot] as JSON (the last-known settings, review fix 11). */
    fun encodeSnapshot(snapshot: SettingsSnapshot): String =
        json.encodeToString(
            StoredSnapshot.serializer(),
            StoredSnapshot(snapshot.settings.baseFeeTier, snapshot.settings.maxSnoozes, snapshot.pending.map { it.toStored() }),
        )

    /** The snapshot stored as [text], or null when it cannot be decoded. */
    fun decodeSnapshot(text: String): SettingsSnapshot? =
        runCatching {
            val stored = json.decodeFromString(StoredSnapshot.serializer(), text)
            SettingsSnapshot(
                GlobalSettings(baseFeeTier = stored.baseFeeTier, maxSnoozes = stored.maxSnoozes),
                stored.pending.map { it.toChange() },
            )
        }.getOrNull()
}

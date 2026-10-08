package com.yawnandpawn.app.core.config

import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.session.GlobalSettings
import kotlin.time.Instant

/** What a save does with one locked field ([CommitmentRules.decide]). */
sealed interface LockDecision {
    /** The value to store as the live setting now. */
    val live: SettingValue

    /** Apply [live] now and drop the field's pending change, if any. */
    data class ApplyNow(
        override val live: SettingValue,
    ) : LockDecision

    /** Keep [live] (the effective value) as the live setting and store [pending], replacing the field's one. */
    data class Defer(
        override val live: SettingValue,
        val pending: PendingChange,
    ) : LockDecision
}

/**
 * The commitment lock (PRD §6.2, AD-16), one decision for every use case that saves a locked field, so the editor and
 * Settings never implement the 8-hour rule differently.
 */
object CommitmentRules {
    /**
     * The value in effect at [now]: [pending]'s value once the occurrence it waits for has passed (due, not yet promoted),
     * otherwise [live].
     */
    fun effective(
        live: SettingValue,
        pending: PendingChange?,
        now: Instant,
    ): SettingValue = pending?.takeIf { it.isDue(now) }?.value ?: live

    /**
     * Saving [new] for the field of [live] (of the alarm [alarmId], or global for null), with the field's stored [pending]
     * change and [window], the occurrence the field is locked for at [now] (null: no alarm in its window).
     * - The change is classified against the effective value ([effective]), so effective $3 with $1 pending and a new $2
     *   is still Weakening.
     * - Strengthening, no change, or any change outside the window: [LockDecision.ApplyNow].
     * - Weakening inside the window: [LockDecision.Defer] with the effective value live (a due change is promoted on the
     *   way) and a pending change for [window]. Saving again the value already pending keeps that change as it is, so
     *   re-saving never moves its date later.
     */
    fun decide(
        alarmId: String?,
        live: SettingValue,
        pending: PendingChange?,
        new: SettingValue,
        window: Occurrence?,
        now: Instant,
    ): LockDecision {
        val effective = effective(live, pending, now)
        return if (window != null && classify(effective, new) == ChangeKind.Weakening) {
            val kept = pending?.takeIf { !it.isDue(now) && it.value == new }
            LockDecision.Defer(effective, kept ?: PendingChange(alarmId, new, window))
        } else {
            LockDecision.ApplyNow(new)
        }
    }
}

/** The pending changes of [alarmId] (its own and the global ones) that apply to a session ringing at [scheduledAt]. */
fun List<PendingChange>.applyingTo(
    alarmId: String,
    scheduledAt: Instant,
): List<PendingChange> = filter { (it.alarmId == null || it.alarmId == alarmId) && it.appliesTo(scheduledAt) }

/** These settings with the global [changes] applied (the caller has picked the ones that apply). */
fun GlobalSettings.withPending(changes: List<PendingChange>): GlobalSettings =
    changes.filter { it.alarmId == null }.fold(this) { settings, change ->
        when (val value = change.value) {
            is SettingValue.BaseFeeTier -> settings.copy(baseFeeTier = value.tier)
            is SettingValue.MaxSnoozes -> settings.copy(maxSnoozes = value.count)
            is SettingValue.GraceSeconds, is SettingValue.Checks -> settings
        }
    }

/**
 * [plan] with the registered code of [live]'s QR/Barcode entry, when both have one: a code re-registered after the change
 * was saved (a lost sticker, Story 3.13) always wins, so a pending plan never waits for a code the user no longer has.
 */
fun CheckPlan.withCodesFrom(live: List<CheckEntry>): CheckPlan {
    val code = live.firstOrNull { it.type == CheckType.QrBarcode }?.code ?: return this
    return copy(entries = entries.map { if (it.type == CheckType.QrBarcode) it.copy(code = code) else it })
}

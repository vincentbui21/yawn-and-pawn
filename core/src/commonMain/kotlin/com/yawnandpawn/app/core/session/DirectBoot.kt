package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.sound.SoundRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Port for whether the user has unlocked the phone since it booted (Direct Boot, AD-15, Story 2.3). Before the first
 * unlock only device-protected storage is open: the ring then uses the [DirectBootSubstitution], snooze says "Unlock
 * your phone to snooze", and billing and crash reporting wait.
 */
interface UserLockState {
    /** True once the user has unlocked the phone since boot. */
    fun isUserUnlocked(): Boolean

    /** The current value, then every change (the phone is unlocked once per boot, so at most one change). */
    fun observe(): Flow<Boolean>

    /** A phone that is always unlocked: the default where nothing reads the lock state yet. */
    object Unlocked : UserLockState {
        override fun isUserUnlocked(): Boolean = true

        override fun observe(): Flow<Boolean> = flowOf(true)
    }
}

/**
 * What a ring may use before the first unlock (FR-ALM-11, AD-15). Pure. With [beforeFirstUnlock] false the config comes
 * back unchanged. Before the first unlock:
 * - a sound that is not a built-in one ([SoundRef.BuiltIn]) becomes the default built-in sound: a system ringtone needs
 *   the media provider, which is not available before the first unlock;
 * - every check entry whose type is not `directBootSafe` becomes [DIRECT_BOOT_CHECK].
 *
 * The frozen `SessionConfig` is never rewritten: the ring's sound and its first check plan are derived from it, so the
 * fee tier, max snoozes and snooze length always stay as they were frozen.
 */
object DirectBootSubstitution {
    /** The check a ring before the first unlock gets for an entry that needs normal storage: Math · Medium · 3 (Story 3.2). */
    val DIRECT_BOOT_CHECK: CheckEntry = CheckPlan.DEFAULT_ENTRY

    fun apply(
        config: SessionConfig,
        beforeFirstUnlock: Boolean,
    ): SessionConfig {
        if (!beforeFirstUnlock) return config
        val sound = config.soundRef.takeIf { SoundRef.parse(it) is SoundRef.BuiltIn } ?: Alarm.DEFAULT_SOUND_REF
        val plan = lockedPlan(config.checkPlan)
        return if (sound == config.soundRef && plan == config.checkPlan) config else config.copy(soundRef = sound, checkPlan = plan)
    }

    /**
     * [plan] before the first unlock: each entry whose type is not Direct Boot safe ([isSafe]) becomes
     * [DIRECT_BOOT_CHECK], one for one, so a check run's pointer and seeds stay valid. The reducer applies it to the
     * resolved plan of each ring that starts, is restored or follows a snooze while locked, and to the fallback plan.
     * Every type is safe until the camera check (Story 3.10), so tests pass [isSafe].
     */
    internal fun lockedPlan(
        plan: CheckPlan,
        isSafe: (CheckEntry) -> Boolean = { it.type.directBootSafe },
    ): CheckPlan =
        if (plan.entries.all(isSafe)) plan else plan.copy(entries = plan.entries.map { if (isSafe(it)) it else DIRECT_BOOT_CHECK })

    /**
     * Whether this ring of [session] had a check entry swapped for [DIRECT_BOOT_CHECK] (Story 3.11): the ring uses the
     * Direct Boot substitutions (`directBootRing`, kept after an unlock in the ring), the fallback did not replace the
     * check, and the ring's own resolution of the chosen plan (the same pick seed the reducer used) holds an entry that is
     * not Direct Boot safe. The wake screens then say "Your phone restarted, so today's check is Math.". A Random pick
     * that was already safe changed nothing, so it gets no note, and an entry `PlanResolver` already made the default
     * one (QR/Barcode without a code) was not swapped. Pure and derived: never stored.
     */
    fun swappedThisRing(session: SessionData): Boolean {
        val run = session.checkRun
        if (!session.directBootRing || run.fallbackUsed) return false
        val resolved = CheckRun.resolvedFor(run.nextRingPlan(session.config.checkPlan), session.sessionId, session.ringIndex)
        return resolved.entries.any { !it.type.directBootSafe }
    }
}

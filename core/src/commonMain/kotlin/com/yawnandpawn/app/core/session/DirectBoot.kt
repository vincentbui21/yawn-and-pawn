package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.alarm.Alarm
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
 * - every check step that is not [isDirectBootSafe] becomes [DIRECT_BOOT_CHECK].
 *
 * The frozen `SessionConfig` is never rewritten: the ring's sound and its first check plan are derived from it, so the
 * fee tier, max snoozes and snooze length always stay as they were frozen.
 */
object DirectBootSubstitution {
    /** The check a ring before the first unlock gets for a step that needs normal storage; Epic 3 makes it Math. */
    val DIRECT_BOOT_CHECK: CheckStep = CheckStep.Placeholder

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
     * The check plan a ring runs: with [beforeFirstUnlock] the [lockedPlan], else [plan] unchanged. The wake service
     * takes the first ring's seeds for it; the reducer applies it to each ring that starts, is restored or follows a
     * snooze while locked, and to the fallback plan.
     */
    fun plan(
        plan: CheckPlan,
        beforeFirstUnlock: Boolean,
    ): CheckPlan = if (beforeFirstUnlock) lockedPlan(plan) else plan

    /**
     * [plan] before the first unlock: each step that is not Direct Boot safe ([isSafe]) becomes [DIRECT_BOOT_CHECK], one
     * for one, so a check run's step index and seeds stay valid. Every step is safe until Epic 3, so tests pass [isSafe].
     */
    internal fun lockedPlan(
        plan: CheckPlan,
        isSafe: (CheckStep) -> Boolean = CheckStep::isDirectBootSafe,
    ): CheckPlan = if (plan.steps.all(isSafe)) plan else CheckPlan(plan.steps.map { if (isSafe(it)) it else DIRECT_BOOT_CHECK })
}

/** The step runs before the first unlock: it needs no credential-protected storage or media. */
val CheckStep.isDirectBootSafe: Boolean
    get() =
        when (this) {
            CheckStep.Placeholder -> true
        }

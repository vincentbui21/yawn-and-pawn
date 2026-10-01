package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.alarm.Alarm
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Everything a wake session needs from settings, resolved once when the alarm fires and frozen in the session state
 * (AD-16): nothing reads live settings while the session runs.
 *
 * @property scheduledAt the occurrence the session rings for.
 * @property testMode a test alarm (FR-ALM-12): it can never charge and history logs it as Test.
 * @property baseFeeTier the base fee B as a `FeeLadder` tier; the price of the first snooze.
 * @property rampStartPercent where "Gradually increase volume" starts, as a percentage of [volumePercent] (a fixed 20).
 */
@Serializable
data class SessionConfig(
    val alarmId: String,
    val label: String?,
    val scheduledAt: Instant,
    val testMode: Boolean,
    val baseFeeTier: Int,
    val maxSnoozes: Int,
    val snoozeLengthMinutes: Int,
    val graceSeconds: Int,
    val vibrateInGrace: Boolean,
    val volumePercent: Int,
    val gradualVolume: Boolean,
    val rampStartPercent: Int,
    val soundRef: String,
    val vibration: Boolean,
    val checkPlan: CheckPlan,
)

/**
 * The global settings a session reads (FR-SET-1), kept in DataStore by `:data`. [graceSeconds] and
 * [snoozeLengthMinutes] are the defaults for new alarms; a session uses the values stored on its alarm.
 */
data class GlobalSettings(
    val baseFeeTier: Int = DEFAULT_BASE_FEE_TIER,
    val maxSnoozes: Int = DEFAULT_MAX_SNOOZES,
    val graceSeconds: Int = Alarm.DEFAULT_GRACE_SECONDS,
    val snoozeLengthMinutes: Int = Alarm.DEFAULT_SNOOZE_LENGTH_MINUTES,
    val vibrateInGrace: Boolean = false,
) {
    companion object {
        const val DEFAULT_BASE_FEE_TIER = 1
        const val DEFAULT_MAX_SNOOZES = 5
    }
}

/**
 * Builds the frozen [SessionConfig] (AD-16). Pure: the caller reads the alarm and the settings and passes them in.
 * Pending changes inside the commitment lock window arrive in Epic 4.
 */
object ConfigResolver {
    /** The config for a session ringing [alarm] at [scheduledAt]; [testMode] for the test alarm. */
    fun resolve(
        alarm: Alarm,
        globalSettings: GlobalSettings,
        testMode: Boolean,
        scheduledAt: Instant,
    ): SessionConfig =
        SessionConfig(
            alarmId = alarm.id,
            label = alarm.label,
            scheduledAt = scheduledAt,
            testMode = testMode,
            baseFeeTier = globalSettings.baseFeeTier,
            maxSnoozes = globalSettings.maxSnoozes,
            snoozeLengthMinutes = alarm.snoozeLengthMinutes,
            graceSeconds = alarm.graceSeconds,
            vibrateInGrace = globalSettings.vibrateInGrace,
            volumePercent = alarm.volumePercent,
            gradualVolume = alarm.gradualVolume,
            rampStartPercent = alarm.rampStartPercent,
            soundRef = alarm.soundRef,
            vibration = alarm.vibration,
            checkPlan = CheckPlan.placeholder(),
        )
}

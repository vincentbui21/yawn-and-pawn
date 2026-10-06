package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.accessibleEntries
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Everything a wake session needs from settings, resolved once when the alarm fires and frozen in the session state
 * (AD-16): nothing reads live settings while the session runs.
 *
 * @property scheduledAt the occurrence the session rings for.
 * @property testMode a test alarm (FR-ALM-12): it can never charge and history logs it as Test.
 * @property baseFeeTier the base fee B as a `FeeLadder` tier; the price of the first snooze.
 * @property rampStartPercent where "Gradually increase volume" starts, as a percentage of the set [volumePercent] (a fixed
 * 20: the ramp starts at a fifth of the set volume); any value 0..100 is valid at any volume. The player's gain follows
 * `rampGain(elapsed, rampStartPercent / 100.0, 30 s)` on top of the alarm stream set to [volumePercent].
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
    /** The Settings default for new alarms (Epic 5); a session uses its alarm's own `vibrateInGrace` (Story 3.4). */
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
    /**
     * The config for a session ringing [alarm] at [scheduledAt]; [testMode] for the test alarm. The check plan is the
     * alarm's [checks] (its `check_config` rows, sorted by position) in its `checkMode` (Story 3.5); an alarm without
     * checks rings the [defaultPlan]. With [accessible] (TalkBack on at the fire, Story 3.8) its Memory Sequence entries
     * use the numbered variant for the whole session: the plan is frozen at the fire, and snooze re-rings reuse it.
     */
    fun resolve(
        alarm: Alarm,
        checks: List<CheckEntry>,
        globalSettings: GlobalSettings,
        testMode: Boolean,
        scheduledAt: Instant,
        accessible: Boolean = false,
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
            // Quiet-time vibration needs vibration itself on (review fix): "Vibration" off never vibrates.
            vibrateInGrace = alarm.vibration && alarm.vibrateInGrace,
            volumePercent = alarm.volumePercent,
            gradualVolume = alarm.gradualVolume,
            // Fixed (owner decision 2026-09-27); alarms saved before Story 1.14 may hold min(20, volume).
            rampStartPercent = Alarm.DEFAULT_RAMP_START_PERCENT,
            soundRef = alarm.soundRef,
            vibration = alarm.vibration,
            checkPlan = if (checks.isEmpty()) defaultPlan() else CheckPlan(alarm.checkMode, accessibleEntries(checks, accessible)),
        )

    /**
     * The plan of a ring without configured checks, and of every test ring: Random · Math · Medium · 3 (Story 3.2,
     * [CheckPlan.default]).
     */
    fun defaultPlan(): CheckPlan = CheckPlan.default()

    /**
     * The config of a test ring (FR-ALM-12, Story 1.18) from the editor's current, possibly unsaved, [draft]: always
     * `testMode`, the alarm id of the draft (or [TEST_ALARM_ID] for a new alarm), a trimmed label (blank means none)
     * and the same fixed ramp start and check plan as a real ring at [scheduledAt].
     */
    fun resolveTest(
        draft: AlarmDraft,
        globalSettings: GlobalSettings,
        scheduledAt: Instant,
    ): SessionConfig =
        SessionConfig(
            alarmId = draft.id ?: TEST_ALARM_ID,
            label = draft.label?.trim()?.takeIf { it.isNotEmpty() },
            scheduledAt = scheduledAt,
            testMode = true,
            baseFeeTier = globalSettings.baseFeeTier,
            maxSnoozes = globalSettings.maxSnoozes,
            snoozeLengthMinutes = draft.snoozeLengthMinutes,
            graceSeconds = draft.graceSeconds,
            vibrateInGrace = draft.vibration && draft.vibrateInGrace,
            volumePercent = draft.volumePercent,
            gradualVolume = draft.gradualVolume,
            rampStartPercent = Alarm.DEFAULT_RAMP_START_PERCENT,
            soundRef = draft.soundRef,
            vibration = draft.vibration,
            // The draft's own checks are not rung yet (Story 3.5 deferred item): a test ring uses the default plan.
            checkPlan = defaultPlan(),
        )

    /** The alarm id of a test ring for an alarm that is not stored yet. */
    const val TEST_ALARM_ID = "test-alarm"
}

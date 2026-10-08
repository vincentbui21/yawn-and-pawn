package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.accessibleEntries
import com.yawnandpawn.app.core.checks.word.WordBank
import com.yawnandpawn.app.core.config.PendingChange
import com.yawnandpawn.app.core.config.SettingValue
import com.yawnandpawn.app.core.config.applyingTo
import com.yawnandpawn.app.core.config.withCodesFrom
import com.yawnandpawn.app.core.config.withPending
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
 * Builds the frozen [SessionConfig] (AD-16). Pure: the caller reads the alarm, the settings and the pending changes
 * (Story 4.4) and passes them in.
 */
object ConfigResolver {
    /**
     * The config for a session ringing [alarm] at [scheduledAt]; [testMode] for the test alarm. The check plan is the
     * alarm's [checks] (its `check_config` rows, sorted by position) in its `checkMode` (Story 3.5); an alarm without
     * checks rings the [defaultPlan]. With [accessible] (TalkBack on at the fire, Story 3.8) its Memory Sequence entries
     * use the numbered variant for the whole session: the plan is frozen at the fire, and snooze re-rings reuse it.
     * Without [wordsAvailable] (the word list failed to load, Story 3.7 review) a Word Unscramble entry could never be
     * solved, so Math takes its place for the session ([ringableEntries]).
     *
     * [pendingChanges] (Story 4.4, the commitment lock) are the stored pending changes: one of [alarm] or a global one
     * applies only to an occurrence strictly after the one it waits for ([PendingChange.appliesTo]), so it is ignored
     * at that occurrence and any earlier one. [alarm], [checks] and [globalSettings] are the live values.
     */
    fun resolve(
        alarm: Alarm,
        checks: List<CheckEntry>,
        globalSettings: GlobalSettings,
        testMode: Boolean,
        scheduledAt: Instant,
        accessible: Boolean = false,
        wordsAvailable: Boolean = WordBank.current.words.isNotEmpty(),
        pendingChanges: List<PendingChange> = emptyList(),
    ): SessionConfig {
        val applying = pendingChanges.applyingTo(alarm.id, scheduledAt)
        val settings = globalSettings.withPending(applying)
        val graceSeconds = applying.firstNotNullOfOrNull { (it.value as? SettingValue.GraceSeconds)?.seconds } ?: alarm.graceSeconds
        val plan =
            applying.firstNotNullOfOrNull { (it.value as? SettingValue.Checks)?.plan?.withCodesFrom(checks) }
                ?: CheckPlan(alarm.checkMode, checks)
        return resolveLive(alarm, plan, settings, graceSeconds, testMode, scheduledAt, accessible, wordsAvailable)
    }

    @Suppress("LongParameterList")
    private fun resolveLive(
        alarm: Alarm,
        plan: CheckPlan,
        globalSettings: GlobalSettings,
        graceSeconds: Int,
        testMode: Boolean,
        scheduledAt: Instant,
        accessible: Boolean,
        wordsAvailable: Boolean,
    ): SessionConfig =
        SessionConfig(
            alarmId = alarm.id,
            label = alarm.label,
            scheduledAt = scheduledAt,
            testMode = testMode,
            baseFeeTier = globalSettings.baseFeeTier,
            maxSnoozes = globalSettings.maxSnoozes,
            snoozeLengthMinutes = alarm.snoozeLengthMinutes,
            graceSeconds = graceSeconds,
            // Quiet-time vibration needs vibration itself on (review fix): "Vibration" off never vibrates.
            vibrateInGrace = alarm.vibration && alarm.vibrateInGrace,
            volumePercent = Alarm.ringableVolume(alarm.volumePercent),
            gradualVolume = alarm.gradualVolume,
            // Fixed (owner decision 2026-09-27); alarms saved before Story 1.14 may hold min(20, volume).
            rampStartPercent = Alarm.DEFAULT_RAMP_START_PERCENT,
            soundRef = alarm.soundRef,
            vibration = alarm.vibration,
            checkPlan =
                if (plan.entries.isEmpty()) {
                    defaultPlan()
                } else {
                    CheckPlan(
                        plan.mode,
                        ringableEntries(plan.entries, accessible, wordsAvailable),
                    )
                },
        )

    /**
     * The plan of a ring (or a test ring) without configured checks: Random · Math · Easy · 3 (Story 3.2,
     * [CheckPlan.default]; Easy since the owner decision of 2026-10-08).
     */
    fun defaultPlan(): CheckPlan = CheckPlan.default()

    /**
     * The config of a test ring (FR-ALM-12, Story 1.18) from the editor's current, possibly unsaved, [draft]: always
     * `testMode`, the alarm id of the draft (or [TEST_ALARM_ID] for a new alarm), a trimmed label (blank means none)
     * and the same fixed ramp start as a real ring at [scheduledAt]. The check plan is the draft's own checks in its
     * mode, a QR/Barcode entry with its (possibly unsaved) code included, built like a real ring's ([resolve]): a draft
     * without checks rings the [defaultPlan], and without [wordsAvailable] Math takes Word Unscramble's place. (Epic 3
     * device check, bug 1: a test used to ring the default plan whatever the alarm's checks.) The numbered Memory
     * Sequence is chosen at the fire ([withAccessibleChecks]), when TalkBack's state is known.
     */
    fun resolveTest(
        draft: AlarmDraft,
        globalSettings: GlobalSettings,
        scheduledAt: Instant,
        wordsAvailable: Boolean = WordBank.current.words.isNotEmpty(),
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
            volumePercent = Alarm.ringableVolume(draft.volumePercent),
            gradualVolume = draft.gradualVolume,
            rampStartPercent = Alarm.DEFAULT_RAMP_START_PERCENT,
            soundRef = draft.soundRef,
            vibration = draft.vibration,
            checkPlan =
                if (draft.checks.isEmpty()) {
                    defaultPlan()
                } else {
                    CheckPlan(draft.checkMode, ringableEntries(draft.checks, accessible = false, wordsAvailable = wordsAvailable))
                },
        )

    /**
     * [config] as the ring that starts now runs it: with [accessible] (TalkBack on at the fire, Story 3.8) its Memory
     * Sequence entries use the numbered variant. A test config is stored when "Test alarm" is tapped and rings up to
     * 10 s later, so its fire applies this, as a real ring's [resolve] does.
     */
    fun withAccessibleChecks(
        config: SessionConfig,
        accessible: Boolean,
    ): SessionConfig {
        val entries = accessibleEntries(config.checkPlan.entries, accessible)
        return if (entries == config.checkPlan.entries) config else config.copy(checkPlan = config.checkPlan.copy(entries = entries))
    }

    /** The alarm id of a test ring for an alarm that is not stored yet. */
    const val TEST_ALARM_ID = "test-alarm"
}

/**
 * The [checks] as a ring runs them: Memory Sequence numbered when [accessible] (Story 3.8), and, when no word list is
 * installed (![wordsAvailable]), no Word Unscramble entry, since it could never be solved (Story 3.7 review): Math at
 * the same difficulty with its default count takes its place, or the entry is dropped when the plan already has Math.
 */
internal fun ringableEntries(
    checks: List<CheckEntry>,
    accessible: Boolean,
    wordsAvailable: Boolean,
): List<CheckEntry> {
    val playable =
        when {
            wordsAvailable || checks.none { it.type == CheckType.WordUnscramble } -> {
                checks
            }

            checks.any { it.type == CheckType.Math } -> {
                checks.filterNot { it.type == CheckType.WordUnscramble }
            }

            else -> {
                checks.map { if (it.type == CheckType.WordUnscramble) mathInPlaceOf(it) else it }
            }
        }
    return accessibleEntries(playable, accessible)
}

/** Math at [word]'s difficulty with Math's default count. */
private fun mathInPlaceOf(word: CheckEntry): CheckEntry = CheckEntry(CheckType.Math, word.difficulty, CheckType.Math.defaultCount)

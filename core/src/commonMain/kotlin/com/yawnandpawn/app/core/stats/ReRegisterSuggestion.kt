package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** A check of an alarm whose code the user registered (QR/Barcode, Story 3.10), and when it was last registered. */
data class ConfiguredCheck(
    val alarmId: String,
    val type: CheckType,
    val registeredAt: Instant,
)

/** One check of one alarm: what a dismissal of the re-register banner is stored under. */
data class CheckKey(
    val alarmId: String,
    val typeId: String,
)

/** Home should suggest re-registering [type] of the alarm [alarmId]: its fallback was used [fallbacks] times this week. */
data class ReRegisterSuggestion(
    val alarmId: String,
    val type: CheckType,
    val fallbacks: Int,
)

/**
 * Whether Home suggests re-registering a camera check (FR-PWK-11, Story 3.9 history, AD-18: derived, never stored).
 * Pure. A check of [alarms] in [checkConfigs] that uses the camera ([usesCamera], the type's own flag in production) is
 * suggested when at least [ReRegisterRule.FALLBACKS] sessions of that alarm:
 * - rang first within [ReRegisterRule.WINDOW] before [now] (`first_ring_at`);
 * - used the fallback with `fallback_from` equal to that check's type id;
 * - were not test sessions;
 * - started after the check's last registration and after the user last dismissed the banner for it ([dismissedAt]), so
 *   a new code or a dismissal waits for 3 new fallbacks.
 *
 * With more than one such check, the one whose last counted fallback is the newest wins.
 */
fun reRegisterSuggestion(
    history: List<SessionHistoryRow>,
    alarms: List<Alarm>,
    checkConfigs: List<ConfiguredCheck>,
    now: Instant,
    dismissedAt: Map<CheckKey, Instant> = emptyMap(),
    usesCamera: (CheckType) -> Boolean = CheckType::usesCamera,
): ReRegisterSuggestion? {
    val alarmIds = alarms.mapTo(mutableSetOf()) { it.id }
    val windowStart = now - ReRegisterRule.WINDOW
    return checkConfigs
        .filter { it.alarmId in alarmIds && usesCamera(it.type) }
        .mapNotNull { check ->
            val dismissed = dismissedAt[CheckKey(check.alarmId, check.type.id)]
            val since = if (dismissed != null && dismissed > check.registeredAt) dismissed else check.registeredAt
            val counted =
                history.filter { row ->
                    row.alarmId == check.alarmId &&
                        row.fallbackUsed &&
                        row.fallbackFrom == check.type.id &&
                        row.outcome != SessionOutcome.Test &&
                        row.firstRingAt > since &&
                        row.firstRingAt >= windowStart &&
                        row.firstRingAt <= now
                }
            counted.maxOfOrNull { it.firstRingAt }?.takeIf { counted.size >= ReRegisterRule.FALLBACKS }?.let { newest ->
                newest to ReRegisterSuggestion(check.alarmId, check.type, counted.size)
            }
        }.maxByOrNull { it.first }
        ?.second
}

/** The thresholds of [reRegisterSuggestion] (FR-PWK-11). */
object ReRegisterRule {
    /** Fallbacks within [WINDOW] that make Home suggest re-registering. */
    const val FALLBACKS = 3

    /** "This week": the last 7 × 24 hours. */
    val WINDOW: Duration = 7.days
}

/** Port: the sessions that used the fallback check, from session history (read only); emits again after every change. */
fun interface FallbackHistory {
    fun observeFallbacks(): Flow<List<SessionHistoryRow>>
}

/**
 * Port: the registered camera checks of the alarms and when each was last registered. Story 3.10 stores them (check
 * config with the code); until then there are none ([None]), so Home never suggests re-registering.
 */
fun interface CheckRegistrations {
    fun observe(): Flow<List<ConfiguredCheck>>

    /** No registered check: the production value until Story 3.10. */
    object None : CheckRegistrations {
        override fun observe(): Flow<List<ConfiguredCheck>> = flowOf(emptyList())
    }
}

/**
 * Port: when the user last dismissed the re-register banner of each check, kept in device-protected DataStore by
 * `:data`. A read failure emits an empty map (the banner shows again rather than never).
 */
interface ReRegisterDismissals {
    fun dismissed(): Flow<Map<CheckKey, Instant>>

    suspend fun dismiss(
        key: CheckKey,
        at: Instant,
    ): Outcome<Unit, DomainError>
}

/** What [reRegisterSuggestion] reads from storage; the alarms and the time come from Home, which has them already. */
data class ReRegisterInputs(
    val fallbacks: List<SessionHistoryRow> = emptyList(),
    val checkConfigs: List<ConfiguredCheck> = emptyList(),
    val dismissedAt: Map<CheckKey, Instant> = emptyMap(),
)

/**
 * Home's re-register banner (Story 3.13): the stored [inputs], the [suggestion] for Home's alarms at the clock's time,
 * and its dismissal. Home combines [inputs] with the alarms it lists and its own ticks, so the 7-day window moves with
 * the clock without a second read of the alarms. Reads only, apart from the dismissal.
 */
class ReRegisterSuggestions(
    private val history: FallbackHistory,
    private val registrations: CheckRegistrations,
    private val dismissals: ReRegisterDismissals,
    private val clock: Clock,
    private val usesCamera: (CheckType) -> Boolean = CheckType::usesCamera,
) {
    /** The stored inputs, again after every change. A failing read throws into the flow. */
    fun inputs(): Flow<ReRegisterInputs> =
        combine(history.observeFallbacks(), registrations.observe(), dismissals.dismissed(), ::ReRegisterInputs)

    /** The suggestion for [alarms] from [inputs] now. */
    fun suggestion(
        inputs: ReRegisterInputs,
        alarms: List<Alarm>,
    ): ReRegisterSuggestion? =
        reRegisterSuggestion(inputs.fallbacks, alarms, inputs.checkConfigs, clock.now(), inputs.dismissedAt, usesCamera)

    /** The user dismissed [suggestion]: it returns only after 3 new fallbacks. */
    suspend fun dismiss(suggestion: ReRegisterSuggestion): Outcome<Unit, DomainError> =
        dismissals.dismiss(CheckKey(suggestion.alarmId, suggestion.type.id), clock.now())
}

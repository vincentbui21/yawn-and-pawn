package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// Core cannot depend on :testing (AD-1), so the session tests use these local doubles and builders; :testing has the
// fakes the other modules use.

/** The moment every session test starts from: boot 3, 1000 s after boot. */
internal val T0 = TimeSnapshot(wallMillis = 1_800_000_000_000, elapsedMillis = 1_000_000, bootCount = 3)

/** [T0] plus [duration] of real time (wall and monotonic together). */
internal fun at(duration: Duration): TimeSnapshot = T0.plus(duration)

internal fun TimeSnapshot.plus(duration: Duration): TimeSnapshot =
    copy(wallMillis = wallMillis + duration.inWholeMilliseconds, elapsedMillis = elapsedMillis + duration.inWholeMilliseconds)

internal const val SESSION_ID = "session-1"
internal const val PRODUCT = "snooze_usd_01"
internal val OFFER = SnoozeOffer(productId = PRODUCT, snoozeNumber = 1)
internal val INTENT = PurchaseIntentId("intent-1")
internal val TOKEN = PurchaseToken("token-1")
internal val SEEDS = listOf(7L)
internal val NEW_SEEDS = listOf(11L, 12L)
internal val SCHEDULED_AT: Instant = Instant.parse("2027-03-03T06:00:00Z")
internal val TWO_STEPS = CheckPlan(listOf(CheckStep.Placeholder, CheckStep.Placeholder))
internal val FALLBACK_PLAN = CheckPlan(listOf(CheckStep.Placeholder, CheckStep.Placeholder, CheckStep.Placeholder))

internal fun testConfig(
    testMode: Boolean = false,
    checkPlan: CheckPlan = CheckPlan.placeholder(),
    vibration: Boolean = true,
    vibrateInGrace: Boolean = false,
): SessionConfig =
    SessionConfig(
        alarmId = "alarm-1",
        label = "Work",
        scheduledAt = SCHEDULED_AT,
        testMode = testMode,
        baseFeeTier = 1,
        maxSnoozes = 5,
        snoozeLengthMinutes = 9,
        graceSeconds = 20,
        vibrateInGrace = vibrateInGrace,
        volumePercent = 80,
        gradualVolume = true,
        rampStartPercent = 20,
        soundRef = "builtin:default",
        vibration = vibration,
        checkPlan = checkPlan,
    )

/** A first ring that started at [T0]: interaction deadline 30 min after it. */
internal fun ringSession(config: SessionConfig = testConfig()): SessionData =
    SessionData(
        sessionId = SESSION_ID,
        config = config,
        ringIndex = 1,
        snoozesGranted = 0,
        checkRun = CheckRun(plan = config.checkPlan, seeds = SEEDS),
        interactionDeadline = Deadline.after(T0, 30.minutes),
    )

/** A snooze granted at [T0]: silent until 9 min later. */
internal fun snoozedSession(): SessionData =
    ringSession().copy(snoozesGranted = 1, interactionDeadline = null, snoozeEnd = Deadline.after(T0, 9.minutes))

/** Ringing, Grace (grace from [T0]) and Loud holding [session]. */
internal fun ringStates(session: SessionData = ringSession()): List<SessionState.Ring> =
    listOf(SessionState.Ringing(session)) + checkStates(session)

/** Grace (grace from [T0]) and Loud holding [session]. */
internal fun checkStates(session: SessionData = ringSession()): List<SessionState.Ring> =
    listOf(SessionState.Grace(session.copy(graceEnd = session.graceEnd ?: Deadline.after(T0, 20.seconds))), SessionState.Loud(session))

/** [this] after a user event at [now]: a fresh 30-minute interaction deadline. */
internal fun SessionData.touched(now: TimeSnapshot): SessionData = copy(interactionDeadline = Deadline.after(now, 30.minutes))

/** [this] with no ring timers, as in Snoozed, Completed and Missed. */
internal fun SessionData.noTimers(): SessionData = copy(graceEnd = null, interactionDeadline = null, snoozeEnd = null, pausedAt = null)

internal class StubAvailability(
    var result: SnoozeAvailability,
) : SnoozeAvailabilityPolicy {
    override fun availability(session: SessionData): SnoozeAvailability = result
}

internal class StubCheck(
    var result: StepResult,
) : CheckValidator {
    val answers = mutableListOf<CheckAnswer>()

    override fun validate(
        run: CheckRun,
        answer: CheckAnswer,
    ): StepResult {
        answers += answer
        return result
    }
}

internal class StubFallback(
    var decision: FallbackDecision,
) : FallbackPolicy {
    override fun fallback(session: SessionData): FallbackDecision = decision
}

/** A reducer whose guards all pass unless told otherwise. */
internal fun reducer(
    availability: SnoozeAvailability = SnoozeAvailability.Available(OFFER),
    check: StepResult = StepResult.ValidLast,
    fallback: FallbackDecision = FallbackDecision.Allowed(FALLBACK_PLAN),
): SessionReducer = SessionReducer(StubAvailability(availability), StubCheck(check), StubFallback(fallback))

/** The reducer Epic 1 ships with. */
internal fun productionReducer(): SessionReducer =
    SessionReducer(NoBillingSnoozeAvailability(), PlaceholderCheckValidator, NoFallbackPolicy)

internal fun ignored(
    state: SessionState,
    event: SessionEvent,
): Transition {
    val eventSession =
        when (event) {
            is SessionEvent.AlarmFired -> event.sessionId
            is SessionEvent.TestAlarmFired -> event.sessionId
            is SessionEvent.Recorded -> event.sessionId
            else -> null
        }
    val sessionId = eventSession ?: (state as? SessionState.Active)?.session?.sessionId
    return Transition(state, listOf(SessionEffect.LogIgnored(event::class.simpleName ?: "SessionEvent", sessionId)))
}

/** The state's name, for example names. */
internal val SessionState.kind: String
    get() = this::class.simpleName ?: "SessionState"

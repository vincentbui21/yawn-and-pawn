package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.billing.LivePrice
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PurchaseIntent
import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.SeedDeriver
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

/** Play's live price of [PRODUCT] at the Pay tap (Story 4.8). */
internal val QUOTE = LivePrice(PRODUCT, Money(1_190_000, "EUR"), "€1.19")

/** The user confirmed paying [QUOTE] for intent [INTENT]. */
internal val PAY = SessionEvent.PayConfirmed(INTENT, QUOTE)

/** The intent a [PAY] in [SESSION_ID] for [OFFER] at [now] persists. */
internal fun intentAt(
    now: TimeSnapshot,
    quote: LivePrice = QUOTE,
    intentId: PurchaseIntentId = INTENT,
    offer: SnoozeOffer = OFFER,
): PurchaseIntent =
    PurchaseIntent(
        intentId = intentId,
        sessionId = SESSION_ID,
        productId = offer.productId,
        snoozeNumber = offer.snoozeNumber,
        price = quote.price,
        formattedPrice = quote.formattedPrice,
        createdAt = Instant.fromEpochMilliseconds(now.wallMillis),
    )

internal val TOKEN = PurchaseToken("token-1")
internal val SEEDS = listOf(7L)
internal val NEW_SEEDS = listOf(11L, 12L)
internal val SCHEDULED_AT: Instant = Instant.parse("2027-03-03T06:00:00Z")
internal val TWO_STEPS = CheckPlan(CheckMode.All, List(2) { CheckPlan.PLACEHOLDER_ENTRY })
internal val FALLBACK_PLAN = CheckPlan(CheckMode.All, List(3) { CheckPlan.PLACEHOLDER_ENTRY })

/** The user picks Math in the Fallback check picker, offered because the camera is unavailable (Story 3.9). */
internal val FALLBACK_REQUEST = SessionEvent.FallbackRequested(CheckType.Math, FallbackReason.CameraUnavailable)

/**
 * The first seeds of the [size] entries of ring [ringIndex] of [SESSION_ID] (the fallback keys with [fallback]), spelled
 * out from [SeedDeriver] rather than through `CheckRun.forRing`, so a test of the reducer can catch a wrong key.
 */
internal fun ringSeeds(
    ringIndex: Int,
    size: Int,
    fallback: Boolean = false,
): List<Long> = List(size) { SeedDeriver.seed(SESSION_ID, ringIndex, it, 0, fallback) }

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
        checkRun = CheckRun(plan = config.checkPlan, seeds = ringSeeds(1, config.checkPlan.entries.size)),
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
    val requests = mutableListOf<FallbackRequest>()

    override fun fallback(
        session: SessionData,
        request: FallbackRequest,
    ): FallbackDecision {
        requests += request
        return decision
    }
}

/** A reducer whose guards all pass unless told otherwise. */
internal fun reducer(
    availability: SnoozeAvailability = SnoozeAvailability.Available(OFFER),
    check: StepResult = StepResult.ValidLast,
    fallback: FallbackDecision = FallbackDecision.Allowed(FALLBACK_PLAN),
): SessionReducer = SessionReducer(StubAvailability(availability), StubCheck(check), StubFallback(fallback))

/** The production reducer: the plugin validator (Story 3.2), no billing and no fallback yet. */
internal fun productionReducer(): SessionReducer =
    SessionReducer(NoBillingSnoozeAvailability(), PluginCheckValidator, CameraFallbackPolicy())

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

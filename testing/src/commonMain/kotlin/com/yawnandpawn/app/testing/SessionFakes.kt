package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.session.CheckAnswer
import com.yawnandpawn.app.core.session.CheckRun
import com.yawnandpawn.app.core.session.CheckValidator
import com.yawnandpawn.app.core.session.FallbackDecision
import com.yawnandpawn.app.core.session.FallbackPolicy
import com.yawnandpawn.app.core.session.FeeLadder
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.SnoozeAvailabilityPolicy
import com.yawnandpawn.app.core.session.SnoozeOffer
import com.yawnandpawn.app.core.session.StepResult
import com.yawnandpawn.app.core.session.UnavailableReason
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.session.nextOffer
import com.yawnandpawn.app.core.session.snoozeProductId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [FeeLadder] under test control: snooze n at base tier B is `snooze_usd_NN` with NN = [tierOf] (B, n), by default
 * B + n - 1. Every call is kept in [calls] as (baseFeeTier, snoozeNumber).
 */
class FakeFeeLadder(
    var tierOf: (baseFeeTier: Int, snoozeNumber: Int) -> Int = { baseFeeTier, snoozeNumber -> baseFeeTier + snoozeNumber - 1 },
) : FeeLadder {
    private val recorded = mutableListOf<Pair<Int, Int>>()

    val calls: List<Pair<Int, Int>>
        get() = recorded.toList()

    override fun offer(
        baseFeeTier: Int,
        snoozeNumber: Int,
    ): SnoozeOffer {
        recorded += baseFeeTier to snoozeNumber
        return SnoozeOffer(productId = snoozeProductId(tierOf(baseFeeTier, snoozeNumber)), snoozeNumber = snoozeNumber)
    }
}

/**
 * [SnoozeAvailabilityPolicy] under test control: Available at the [ladder]'s next offer unless [unavailable] names a
 * reason. Every session asked about is kept in [asked].
 */
class FakeSnoozeAvailability(
    val ladder: FeeLadder = FakeFeeLadder(),
    var unavailable: UnavailableReason? = null,
) : SnoozeAvailabilityPolicy {
    private val sessions = mutableListOf<SessionData>()

    val asked: List<SessionData>
        get() = sessions.toList()

    override fun availability(session: SessionData): SnoozeAvailability {
        sessions += session
        return unavailable?.let { SnoozeAvailability.Unavailable(it) } ?: SnoozeAvailability.Available(ladder.nextOffer(session))
    }
}

/**
 * [CheckValidator] under test control: returns the queued results in order ([willReturn]), then [default]. Use
 * [StepResult.Invalid], [StepResult.ValidNext] or [StepResult.ValidLast]. Every answer is kept in [answers].
 */
class FakeCheck(
    var default: StepResult = StepResult.ValidLast,
) : CheckValidator {
    private val queued = ArrayDeque<StepResult>()
    private val submitted = mutableListOf<CheckAnswer>()

    val answers: List<CheckAnswer>
        get() = submitted.toList()

    /** The next validations return [results], one each, before falling back to [default]. */
    fun willReturn(vararg results: StepResult) {
        queued.addAll(results)
    }

    override fun validate(
        run: CheckRun,
        answer: CheckAnswer,
    ): StepResult {
        submitted += answer
        return queued.removeFirstOrNull() ?: default
    }
}

/** [FallbackPolicy] under test control: returns [decision] (not allowed by default) and counts the calls in [calls]. */
class FakeFallbackPolicy(
    var decision: FallbackDecision = FallbackDecision.NotAllowed,
) : FallbackPolicy {
    var calls: Int = 0
        private set

    override fun fallback(session: SessionData): FallbackDecision {
        calls++
        return decision
    }
}

/** [UserLockState] under test control (Story 2.3): locked until [unlock] (or [unlocked] set true). */
class FakeUserLockState(
    unlocked: Boolean = false,
) : UserLockState {
    private val state = MutableStateFlow(unlocked)

    var unlocked: Boolean
        get() = state.value
        set(value) {
            state.value = value
        }

    /** The user unlocks the phone. */
    fun unlock() {
        unlocked = true
    }

    override fun isUserUnlocked(): Boolean = state.value

    override fun observe(): Flow<Boolean> = state
}

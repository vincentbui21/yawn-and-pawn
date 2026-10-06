package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.SeedDeriver
import com.yawnandpawn.app.core.time.Deadline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Story 3.9: the production fallback policy and the fallback in the session. No camera check type exists before Story
 * 3.10, so the placeholder stands in for one ([cameraStandIn]); `FakeCameraCheck` in `:testing` does the same.
 */
class CameraFallbackPolicyTest {
    private val cameraStandIn: (CheckType) -> Boolean = { it == CheckType.Placeholder }
    private val policy = CameraFallbackPolicy(cameraStandIn)

    /** A camera check entry followed by Math, on its first entry with [failedAttempts]. */
    private fun cameraSession(failedAttempts: Int = 0): SessionData {
        val plan = CheckPlan(CheckMode.All, listOf(CheckPlan.PLACEHOLDER_ENTRY, CheckPlan.DEFAULT_ENTRY))
        return ringSession(testConfig(checkPlan = plan)).let {
            it.copy(checkRun = it.checkRun.copy(failedAttempts = failedAttempts))
        }
    }

    private fun request(
        type: CheckType = CheckType.Math,
        reason: FallbackReason = FallbackReason.CameraUnavailable,
    ) = FallbackRequest(type, reason)

    private val mathHard6 = CheckPlan(CheckMode.All, listOf(CheckEntry(CheckType.Math, Difficulty.Hard, count = 6)))

    @Test
    fun `a camera check falls back to Math at Hard with twice its default count`() {
        assertEquals(FallbackDecision.Allowed(mathHard6), policy.fallback(cameraSession(), request()))
        assertEquals(mathHard6, CameraFallbackPolicy.fallbackPlan(CheckType.Math))
        assertEquals(
            CheckConfig.PICKABLE_TYPES.filterNot { it.usesCamera },
            CheckType.fallbackChoices,
            "every pickable check without the camera",
        )
        assertEquals(listOf(CheckType.Math, CheckType.WordUnscramble, CheckType.MemorySequence()), CheckType.fallbackChoices, "Math first")
    }

    @Test
    fun `Word Unscramble and either Memory Sequence variant are fallback choices, kept as asked`() {
        val word = CheckType.WordUnscramble
        val numbered = CheckType.MemorySequence(numbered = true)

        assertEquals(FallbackDecision.Allowed(CameraFallbackPolicy.fallbackPlan(word)), policy.fallback(cameraSession(), request(word)))
        assertEquals(
            FallbackDecision.Allowed(CameraFallbackPolicy.fallbackPlan(numbered)),
            policy.fallback(cameraSession(), request(numbered)),
            "the numbered variant (TalkBack on) stays numbered",
        )
    }

    @Test
    fun `with a working camera the fallback waits for 5 failed attempts`() {
        val reason = FallbackReason.FailedAttempts

        assertEquals(FallbackDecision.NotAllowed, policy.fallback(cameraSession(failedAttempts = 4), request(reason = reason)))
        assertEquals(FallbackDecision.Allowed(mathHard6), policy.fallback(cameraSession(failedAttempts = 5), request(reason = reason)))
        assertEquals(
            FallbackDecision.Allowed(mathHard6),
            policy.fallback(cameraSession(failedAttempts = 0), request()),
            "camera unavailable: at once",
        )
    }

    @Test
    fun `no fallback for a check without the camera, a used fallback, a camera choice or a passed check`() {
        val onMath = cameraSession().let { it.copy(checkRun = it.checkRun.copy(step = StepPointer(1, 0))) }
        val used = cameraSession().let { it.copy(checkRun = it.checkRun.copy(fallbackUsed = true)) }
        val passed = cameraSession().let { it.copy(checkRun = it.checkRun.copy(step = StepPointer(2, 0))) }
        val mathIsCamera = CameraFallbackPolicy { it == CheckType.Placeholder || it == CheckType.Math }

        assertEquals(FallbackDecision.NotAllowed, policy.fallback(onMath, request()), "Math needs no camera")
        assertEquals(FallbackDecision.NotAllowed, policy.fallback(used, request()), "once per session")
        assertEquals(FallbackDecision.NotAllowed, mathIsCamera.fallback(cameraSession(), request()), "the chosen check needs the camera")
        assertEquals(FallbackDecision.NotAllowed, policy.fallback(cameraSession(), request(CheckType.Placeholder)), "not a fallback choice")
        assertEquals(FallbackDecision.NotAllowed, policy.fallback(passed, request()))
        assertEquals(FallbackDecision.NotAllowed, CameraFallbackPolicy().fallback(cameraSession(), request()), "no camera type yet")
    }

    @Test
    fun `the link is offered exactly when Math would be allowed`() {
        assertTrue(policy.offers(cameraSession(), FallbackReason.CameraUnavailable))
        assertFalse(policy.offers(cameraSession(failedAttempts = 4), FallbackReason.FailedAttempts))
        assertTrue(policy.offers(cameraSession(failedAttempts = 5), FallbackReason.FailedAttempts))
        val mathAlarm = ringSession(testConfig(checkPlan = CheckPlan(CheckMode.All, listOf(CheckPlan.DEFAULT_ENTRY))))
        assertFalse(policy.offers(mathAlarm, FallbackReason.CameraUnavailable), "a Math alarm has no link")
    }

    @Test
    fun `the fallback replaces the check once, keeps the timers, and stays with new seeds after a snooze`() {
        val reducer = SessionReducer(StubAvailability(SnoozeAvailability.Available(OFFER)), PluginCheckValidator, policy)
        val grace = SessionState.Grace(cameraSession(failedAttempts = 2).copy(graceEnd = Deadline.after(T0, 20.seconds)))
        val event = SessionEvent.FallbackRequested(CheckType.Math, FallbackReason.CameraUnavailable)

        val fallen = reducer.reduce(grace, event, at(1.minutes))
        val session = assertIs<SessionState.Grace>(fallen.state).session
        assertEquals(listOf<SessionEffect>(SessionEffect.StartCheckStep(0)), fallen.effects)
        assertEquals(grace.session.graceEnd, session.graceEnd, "no new grace window")
        assertEquals(mathHard6, session.checkRun.plan)
        assertEquals(
            listOf(SeedDeriver.seed(SESSION_ID, 1, 0, 0, fallback = true)),
            session.checkRun.seeds,
            "the fallback's own seed",
        )
        assertEquals(StepPointer(0, 0) to 0, session.checkRun.step to session.checkRun.failedAttempts)
        assertEquals("Placeholder", session.checkRun.fallbackFrom)

        // Once per session: a second request changes nothing.
        val again = reducer.reduce(fallen.state, event, at(2.minutes))
        assertEquals(session.checkRun, (again.state as SessionState.Active).session.checkRun)
        assertEquals(emptyList(), again.effects.filterIsInstance<SessionEffect.StartCheckStep>())

        // A paid snooze, then the next ring: still the fallback, with ring 2's fallback seed, never the camera check.
        val snoozed = reducer.reduce(fallen.state, SessionEvent.PurchaseGranted(PRODUCT, TOKEN, PurchaseVerdict.Grant), at(3.minutes)).state
        val ring2 = assertIs<SessionState.Ringing>(reducer.reduce(snoozed, SessionEvent.SlotFired, at(13.minutes)).state).session
        assertEquals(mathHard6, ring2.checkRun.plan)
        assertTrue(ring2.checkRun.fallbackUsed)
        assertEquals("Placeholder", ring2.checkRun.fallbackFrom)
        assertEquals(listOf(SeedDeriver.seed(SESSION_ID, 2, 0, 0, fallback = true)), ring2.checkRun.seeds)
        assertNotEquals(session.checkRun.seeds, ring2.checkRun.seeds)
    }

    @Test
    fun `a Ringing session cannot fall back before I'm up`() {
        val reducer = SessionReducer(StubAvailability(SnoozeAvailability.Available(OFFER)), PluginCheckValidator, policy)
        val ringing = SessionState.Ringing(cameraSession())

        val result = reducer.reduce(ringing, SessionEvent.FallbackRequested(CheckType.Math, FallbackReason.CameraUnavailable), T0)

        assertNull((result.state as SessionState.Active).session.checkRun.fallbackFrom)
        assertFalse(result.state.session.checkRun.fallbackUsed)
    }
}

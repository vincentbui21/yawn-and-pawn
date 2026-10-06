package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class SessionPoliciesTest {
    @Test
    fun `the Epic 1 availability says test mode for test sessions and catalogue not loaded otherwise`() {
        assertEquals(
            SnoozeAvailability.Unavailable(UnavailableReason.TestMode),
            NoBillingSnoozeAvailability().availability(ringSession(testConfig(testMode = true))),
        )
        assertEquals(
            SnoozeAvailability.Unavailable(UnavailableReason.CatalogueNotLoaded),
            NoBillingSnoozeAvailability().availability(ringSession()),
        )
    }

    @Test
    fun `the reasons are the AD-7 set`() {
        assertEquals(
            listOf(
                "TestMode",
                "Offline",
                "BeforeFirstUnlock",
                "CatalogueNotLoaded",
                "MaxSnoozesReached",
                "PriceCapReached",
                "PaymentPending",
                "EarlierPaymentRefunding",
            ),
            UnavailableReason.entries.map { it.name },
        )
    }

    @Test
    fun `a test session is never offered a snooze even if the policy would`() {
        val reducer = reducer(availability = SnoozeAvailability.Available(OFFER))
        assertEquals(
            SnoozeAvailability.Unavailable(UnavailableReason.TestMode),
            reducer.snoozeAvailability(ringSession(testConfig(testMode = true))),
        )
        assertEquals(SnoozeAvailability.Available(OFFER), reducer.snoozeAvailability(ringSession()))
    }

    @Test
    fun `the plugin validator passes a stored placeholder entry with the placeholder answer`() {
        val run = CheckRun(CheckPlan.placeholder(), SEEDS)
        assertEquals(StepResult.ValidLast, PluginCheckValidator.validate(run, CheckAnswer.Placeholder))
        assertEquals(StepResult.ValidNext, PluginCheckValidator.validate(CheckRun(TWO_STEPS, NEW_SEEDS), CheckAnswer.Placeholder))
        assertEquals(
            StepResult.ValidLast,
            PluginCheckValidator.validate(CheckRun(TWO_STEPS, NEW_SEEDS, step = StepPointer(1, 0)), CheckAnswer.Placeholder),
        )
    }

    @Test
    fun `the plugin validator rejects other answers on a placeholder entry and a run past its last step`() {
        val run = CheckRun(CheckPlan.placeholder(), SEEDS)
        assertEquals(StepResult.Invalid, PluginCheckValidator.validate(run, CheckAnswer.ImageMatched))
        val finished = run.copy(step = StepPointer(1, 0))
        assertNull(finished.currentEntry)
        assertEquals(StepResult.Invalid, PluginCheckValidator.validate(finished, CheckAnswer.Placeholder))
    }

    @Test
    fun `the tier ladder maps base tier and snooze number to snooze_usd_NN, capped at 50`() {
        assertEquals(SnoozeOffer("snooze_usd_01", 1), TierFeeLadder.offer(baseFeeTier = 1, snoozeNumber = 1))
        assertEquals(SnoozeOffer("snooze_usd_04", 2), TierFeeLadder.offer(baseFeeTier = 3, snoozeNumber = 2))
        assertEquals(SnoozeOffer("snooze_usd_50", 9), TierFeeLadder.offer(baseFeeTier = 48, snoozeNumber = 9))
        assertFailsWith<IllegalArgumentException> { TierFeeLadder.offer(baseFeeTier = 0, snoozeNumber = 1) }
        assertFailsWith<IllegalArgumentException> { TierFeeLadder.offer(baseFeeTier = 1, snoozeNumber = 0) }
    }

    @Test
    fun `the next offer is the ladder at the base tier and snoozes granted plus one`() {
        val session = ringSession().copy(snoozesGranted = 2)
        assertEquals(SnoozeOffer("snooze_usd_03", 3), TierFeeLadder.nextOffer(session))
        assertEquals("snooze_usd_10", snoozeProductId(10))
    }

    @Test
    fun `a matched image is checked by the validator as an image answer`() {
        val check = StubCheck(StepResult.ValidLast)
        val reducer =
            SessionReducer(StubAvailability(SnoozeAvailability.Available(OFFER)), check, StubFallback(FallbackDecision.NotAllowed))
        reducer.reduce(SessionState.Loud(ringSession()), SessionEvent.ImageMatchCompleted(matched = true), T0)
        assertEquals(listOf<CheckAnswer>(CheckAnswer.ImageMatched), check.answers)
    }

    @Test
    fun `a second PayConfirmed while paying never persists another intent or launches billing again`() {
        val paying = SessionState.Loud(ringSession().copy(paying = INTENT))
        val again = reducer().reduce(paying, SessionEvent.PayConfirmed(PurchaseIntentId("intent-2")), at(5.minutes))
        assertEquals(Transition(paying.with(paying.session.touched(at(5.minutes))), emptyList()), again)
    }

    @Test
    fun `a test session never snoozes from a grant or a reused payment and never consumes`() {
        val test = ringSession(testConfig(testMode = true))
        ringStates(test).forEach { from ->
            val grant = SessionEvent.PurchaseGranted(PRODUCT, TOKEN, PurchaseVerdict.Grant)
            assertEquals(ignored(from, grant), reducer().reduce(from, grant, at(5.minutes)), from.kind)
            val reuse = SessionEvent.ReuseAccepted(PRODUCT, TOKEN)
            val touchedOnly = Transition(from.with(from.session.touched(at(5.minutes))), emptyList())
            assertEquals(touchedOnly, reducer().reduce(from, reuse, at(5.minutes)), from.kind)
        }
    }

    @Test
    fun `a purchase token never shows its value`() {
        assertEquals("PurchaseToken(redacted)", TOKEN.toString())
        assertFalse("token-1" in SessionEvent.PurchaseGranted(PRODUCT, TOKEN, PurchaseVerdict.Grant).toString())
        assertEquals(PurchaseToken("token-1"), TOKEN)
        assertEquals(PurchaseToken("token-1").hashCode(), TOKEN.hashCode())
        assertNotEquals(PurchaseToken("token-2"), TOKEN)
        assertNotEquals<Any>("token-1", TOKEN)
    }

    @Test
    fun `the Epic 1 reducer runs a whole morning without a snooze`() {
        val reducer = productionReducer()
        val config = testConfig()
        var state: SessionState = SessionState.Idle
        state = reducer.reduce(state, SessionEvent.AlarmFired(SESSION_ID, config, beforeFirstUnlock = false), T0).state
        assertEquals(emptyList(), reducer.reduce(state, SessionEvent.SnoozeTapped, T0).effects, "snooze is never offered in Epic 1")
        state = reducer.reduce(state, SessionEvent.ImUpTapped, at(1.minutes)).state
        assertIs<SessionState.Grace>(state)
        val done = reducer.reduce(state, SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder), at(2.minutes))
        assertIs<SessionState.Completed>(done.state)
        assertEquals(listOf(SessionEffect.StopSound, SessionEffect.CancelSlot, SessionEffect.PlayMotivation), done.effects)
        assertEquals(listOf(EntryEffect.HistoryWriteRequested(SESSION_ID)), entryEffects(done.state), "the outcome is written on entry")
        assertEquals(SessionState.Idle, reducer.reduce(done.state, SessionEvent.Recorded(SESSION_ID), at(3.minutes)).state)
    }
}

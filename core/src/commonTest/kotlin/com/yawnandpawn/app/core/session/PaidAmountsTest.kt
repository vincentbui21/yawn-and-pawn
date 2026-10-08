package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.billing.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

/**
 * Story 4.7: `SessionData.paid` (wake-screen display only) grows by the price of each paid snooze, from a grant or a
 * reused payment; no AD-2 row changes, so an unknown price adds nothing and a test session still never snoozes.
 */
class PaidAmountsTest {
    private val reducer = reducer()
    private val one = Money.of(1, "USD")
    private val two = Money.of(2, "USD")

    private fun snoozedBy(
        from: SessionState,
        event: SessionEvent,
    ): SessionData = assertIs<SessionState.Snoozed>(reducer.reduce(from, event, at(1.minutes)).state).session

    @Test
    fun `a grant with its price appends it, and a reused payment appends its own`() {
        val grant = SessionEvent.PurchaseGranted(PRODUCT, TOKEN, PurchaseVerdict.Grant, price = one)
        val first = snoozedBy(SessionState.Ringing(ringSession()), grant)
        assertEquals(listOf(one), first.paid)

        val second = snoozedBy(SessionState.Loud(first.copy(snoozeEnd = null)), SessionEvent.ReuseAccepted(PRODUCT, TOKEN, price = two))
        assertEquals(listOf(one, two), second.paid)
    }

    @Test
    fun `a paid snooze with no known price adds nothing`() {
        val session = ringSession().copy(paid = listOf(one))

        val grant = SessionEvent.PurchaseGranted(PRODUCT, TOKEN, PurchaseVerdict.Grant)
        assertEquals(listOf(one), snoozedBy(SessionState.Grace(session), grant).paid)
        assertEquals(listOf(one), snoozedBy(SessionState.Ringing(session), SessionEvent.ReuseAccepted(PRODUCT, TOKEN)).paid)
    }

    @Test
    fun `a test session never pays, whatever price an event carries`() {
        val test = SessionState.Ringing(ringSession(testConfig(testMode = true)))
        val granted = reducer.reduce(test, SessionEvent.PurchaseGranted(PRODUCT, TOKEN, PurchaseVerdict.Grant, price = one), T0)

        assertEquals(test, granted.state)
    }
}

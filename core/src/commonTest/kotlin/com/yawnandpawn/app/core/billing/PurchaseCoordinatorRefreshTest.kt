package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Epic 4 device follow-up 2 and its review (PR #73): before a launch, when Play's list may lag a payment of the launched
 * product, `PurchaseCoordinator` asks Play once more after [PurchaseCoordinator.PRE_LAUNCH_REFRESH], so a just-approved
 * payment shows the reuse sheet instead of Play's "You already own this item."; never twice for the same pending token or
 * update, never after the ring stopped paying, with `ITEM_ALREADY_OWNED` recovery as the fallback.
 */
class PurchaseCoordinatorRefreshTest {
    private val tok = PurchaseToken("tok-1")

    /**
     * Epic 4 device follow-up 2 (Story 4.18 item 8): a slow payment of yesterday is approved just before the next Pay, but
     * Play's list still shows it pending. Launching would get Play's own "You already own this item." first; the list is
     * asked again after [PurchaseCoordinator.PRE_LAUNCH_REFRESH] and the reuse sheet shows, with Play never opened.
     */
    @Test
    fun `a payment approved after the pre-launch query shows the reuse sheet, never Play's already-owned dialog`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.play.own(purchase(profileId = "yesterday", pending = true))
            w.play.onQuery = { n -> if (n == 2) w.play.own(purchase(profileId = "yesterday")) }

            w.pay()
            assertEquals(emptyList(), w.play.launches, "Play does not open on a list that may lag")
            assertEquals(1, w.play.queries)

            w.advance(PurchaseCoordinator.PRE_LAUNCH_REFRESH)

            assertEquals(listOf<SessionEffect>(SessionEffect.ShowReuseSheet(PRODUCT_1)), w.outcomes)
            assertEquals(emptyList(), w.play.launches, "never launched while a reuse is possible")
            assertEquals(2, w.play.queries)
            assertEquals(
                PRODUCT_1,
                w.coordinator.reuseOffer.value
                    ?.productId,
            )
            assertEquals(emptyList(), w.play.calls, "nothing consumed before the user says yes")
        }

    @Test
    fun `a payment stranded by an update but missing from Play's list is asked again, then offered for reuse`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            // Its approval arrived as an update and was recorded stranded; Play's list has not caught up yet.
            w.buy(purchase(profileId = "yesterday"))
            assertEquals(RecordStatus.Stranded, w.world.recordRows[tok.hash()]?.status)
            w.play.listed.remove(tok)
            w.play.onQuery = { n -> if (n == 2) w.play.own(purchase(profileId = "yesterday")) }

            w.pay()
            w.advance(PurchaseCoordinator.PRE_LAUNCH_REFRESH)

            assertEquals(listOf<SessionEffect>(SessionEffect.ShowReuseSheet(PRODUCT_1)), w.outcomes)
            assertEquals(emptyList(), w.play.launches)
        }

    @Test
    fun `a list still lagging after the second query opens Play, and ITEM_ALREADY_OWNED still recovers the reuse`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.play.own(purchase(profileId = "yesterday", pending = true))
            w.play.launchResults += Outcome.Success(LaunchResult.ItemAlreadyOwned)
            w.play.onQuery = { n -> if (n == 3) w.play.own(purchase(profileId = "yesterday")) }

            w.pay()
            w.advance(PurchaseCoordinator.PRE_LAUNCH_REFRESH)

            assertEquals(1, w.play.launches.size, "the fallback: Play opens once")
            assertEquals(3, w.play.queries, "two before the launch, one for ITEM_ALREADY_OWNED")
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowReuseSheet(PRODUCT_1)), w.outcomes)
            assertEquals(emptyList(), w.play.calls)
        }

    @Test
    fun `a list with nothing of the launched product opens Play at once, with one query`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.play.own(purchase(productId = PRODUCT_3, profileId = "yesterday", pending = true))

            w.pay()

            assertEquals(1, w.play.launches.size)
            assertEquals(1, w.play.queries)
        }

    @Test
    fun `a second query that fails opens Play exactly once`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.play.own(purchase(profileId = "yesterday", pending = true))
            w.play.onQuery = { n -> if (n == 2) w.play.queryFailure = DomainError.BillingUnavailable("timeout") }

            w.pay()
            w.advance(PurchaseCoordinator.PRE_LAUNCH_REFRESH)

            assertEquals(1, w.play.launches.size)
            assertEquals(2, w.play.queries)
        }

    /** Review fix 4: a pending payment that never resolves delays one Pay of its product, not every one. */
    @Test
    fun `a pending payment that never resolves is asked again once, not at every Pay`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.play.own(purchase(profileId = "yesterday", pending = true))

            w.pay()
            w.advance(PurchaseCoordinator.PRE_LAUNCH_REFRESH)
            assertEquals(1, w.play.launches.size)
            w.deliver(PurchaseUpdate.Cancelled)

            w.pay("intent-2")

            assertEquals(2, w.play.launches.size, "the second Pay opens Play at once")
            assertEquals(3, w.play.queries, "two for the first Pay, one for the second")
        }

    @Test
    fun `a product stranded by an update is asked again once per update`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.buy(purchase(profileId = "yesterday"))
            w.play.listed.remove(tok)

            w.pay()
            w.advance(PurchaseCoordinator.PRE_LAUNCH_REFRESH)
            assertEquals(1, w.play.launches.size, "still missing after the second query: Play opens")
            assertEquals(2, w.play.queries)
            w.deliver(PurchaseUpdate.Cancelled)

            w.pay("intent-2")

            assertEquals(2, w.play.launches.size)
            assertEquals(3, w.play.queries, "no second query for the same update")
        }

    /** Review fix 3: the flag is cleared with the first query, so an update during the wait keeps it for the next Pay. */
    @Test
    fun `an update that strands the product during the wait keeps its flag for the next Pay`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.play.own(purchase(token = "tok-pending", profileId = "yesterday", pending = true))

            w.pay()
            // During the wait the approval of another payment arrives; Play's list does not show it yet.
            w.buy(purchase(profileId = "earlier"))
            w.play.listed.remove(tok)
            w.advance(PurchaseCoordinator.PRE_LAUNCH_REFRESH)
            assertEquals(1, w.play.launches.size)
            w.deliver(PurchaseUpdate.Cancelled)

            w.play.onQuery = { n -> if (n == 4) w.play.own(purchase(profileId = "earlier")) }
            w.pay("intent-2")
            w.advance(PurchaseCoordinator.PRE_LAUNCH_REFRESH)

            assertEquals(4, w.play.queries, "the second Pay asked again for the update's product")
            assertEquals(1, w.play.launches.size, "the reuse is offered instead of a second launch")
            assertEquals(1, w.outcomes.count { it == SessionEffect.ShowReuseSheet(PRODUCT_1) })
        }

    /** Review fix 2: nothing is asked or opened for a payment the ring no longer waits for. */
    @Test
    fun `a ring that ends during the wait asks nothing more and opens nothing`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.play.own(purchase(profileId = "yesterday", pending = true))
            w.play.onQuery = { n -> if (n == 2) w.play.own(purchase(profileId = "yesterday")) }

            w.pay()
            w.dispatch(SessionEvent.ImUpTapped)
            w.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))
            w.advance(PurchaseCoordinator.PRE_LAUNCH_REFRESH)

            assertEquals(1, w.play.queries)
            assertEquals(emptyList(), w.play.launches)
            assertTrue(w.outcomes.none { it is SessionEffect.ShowReuseSheet })
        }

    @Test
    fun `a recovery during the wait leaves one reuse offer and no launch`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.play.own(purchase(profileId = "yesterday", pending = true))
            w.play.onQuery = { n -> if (n == 2) w.play.own(purchase(profileId = "yesterday")) }

            w.pay()
            w.recover()
            w.advance(PurchaseCoordinator.PRE_LAUNCH_REFRESH)

            assertEquals(3, w.play.queries, "the pre-launch query, the recovery and the second pre-launch query")
            assertEquals(emptyList(), w.play.launches)
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowReuseSheet(PRODUCT_1)), w.outcomes)
            assertEquals(
                PRODUCT_1,
                w.coordinator.reuseOffer.value
                    ?.productId,
            )
        }

    @Test
    fun `an approval delivered during the wait is offered for reuse, never launched`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.play.own(purchase(profileId = "yesterday", pending = true))

            w.pay()
            w.buy(purchase(profileId = "yesterday"))
            assertEquals(RecordStatus.Stranded, w.world.recordRows[tok.hash()]?.status, "the update recorded it stranded")
            w.advance(PurchaseCoordinator.PRE_LAUNCH_REFRESH)

            assertEquals(emptyList(), w.play.launches)
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowReuseSheet(PRODUCT_1)), w.outcomes)
            assertEquals(emptyList(), w.play.calls, "nothing consumed before the user says yes")
        }
}

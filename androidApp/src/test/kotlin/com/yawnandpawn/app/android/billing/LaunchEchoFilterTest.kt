package com.yawnandpawn.app.android.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 4.12: each sheet result is delivered once (the echo rule of Story 4.11). */
class LaunchEchoFilterTest {
    private var now = 0L
    private val filter = LaunchEchoFilter(windowMillis = 5_000, nowMillis = { now })

    private fun update(
        code: Int,
        purchases: List<PlayPurchase>? = null,
    ) = LaunchEchoFilter.Update(PlayResult(code), purchases)

    @Test
    fun `an echo during the launch is dropped, other updates held then delivered in order`() {
        val purchase = update(BillingResponseCode.OK, listOf(FakeBillingClientFacade.purchase("snooze_usd_01")))
        filter.begin()
        assertTrue(filter.intercept(purchase))
        assertTrue(filter.intercept(update(BillingResponseCode.ITEM_ALREADY_OWNED)))

        val out = filter.end(BillingResponseCode.ITEM_ALREADY_OWNED)

        assertEquals(listOf(purchase), out)
        assertFalse(filter.intercept(update(BillingResponseCode.ITEM_ALREADY_OWNED)), "nothing armed: the echo already came")
    }

    @Test
    fun `an echo after the launch is dropped once within the window`() {
        filter.begin()
        assertEquals(emptyList(), filter.end(BillingResponseCode.SERVICE_UNAVAILABLE))

        assertFalse(filter.intercept(update(BillingResponseCode.USER_CANCELED)), "another code passes")
        assertTrue(filter.intercept(update(BillingResponseCode.SERVICE_UNAVAILABLE)))
        assertFalse(filter.intercept(update(BillingResponseCode.SERVICE_UNAVAILABLE)), "only once")
    }

    @Test
    fun `a copy after the window is delivered`() {
        filter.begin()
        filter.end(BillingResponseCode.ITEM_ALREADY_OWNED)
        now = 5_001

        assertFalse(filter.intercept(update(BillingResponseCode.ITEM_ALREADY_OWNED)))
    }

    @Test
    fun `an OK launch arms nothing, and purchases are never dropped`() {
        filter.begin()
        filter.end(BillingResponseCode.OK)
        assertFalse(filter.intercept(update(BillingResponseCode.USER_CANCELED)))

        filter.begin()
        filter.end(BillingResponseCode.ERROR)
        assertFalse(filter.intercept(update(BillingResponseCode.OK, listOf(FakeBillingClientFacade.purchase("snooze_usd_01")))))
    }
}

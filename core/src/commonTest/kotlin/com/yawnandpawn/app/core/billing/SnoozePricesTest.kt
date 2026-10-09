package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SnoozeOffer
import com.yawnandpawn.app.core.session.ringSession
import com.yawnandpawn.app.core.session.testConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 4.13: the confirm sheet's price ports, next product and tax note. */
class SnoozePricesTest {
    private fun session(
        baseFeeTier: Int = 1,
        maxSnoozes: Int = 5,
    ) = ringSession(testConfig().copy(baseFeeTier = baseFeeTier, maxSnoozes = maxSnoozes))

    @Test
    fun `the next product is snooze n plus 1 at the frozen base fee`() {
        assertEquals("snooze_usd_02", UsdFeeLadder.followingProduct(session(), SnoozeOffer("snooze_usd_01", 1)))
        assertEquals("snooze_usd_09", UsdFeeLadder.followingProduct(session(baseFeeTier = 3), SnoozeOffer("snooze_usd_06", 2)))
    }

    @Test
    fun `there is no next product at the last allowed snooze, over the cap or with an invalid fee`() {
        assertNull(UsdFeeLadder.followingProduct(session(maxSnoozes = 3), SnoozeOffer("snooze_usd_03", 3)), "max snoozes")
        assertNull(UsdFeeLadder.followingProduct(session(baseFeeTier = 10), SnoozeOffer("snooze_usd_50", 5)), "past max")
        assertEquals(
            "snooze_usd_50",
            UsdFeeLadder.followingProduct(session(baseFeeTier = 10), SnoozeOffer("snooze_usd_40", 4)),
            "B x 5 = 50",
        )
        assertNull(UsdFeeLadder.followingProduct(session(baseFeeTier = 9), SnoozeOffer("snooze_usd_45", 5)), "max reached")
        assertNull(UsdFeeLadder.followingProduct(session(baseFeeTier = 20), SnoozeOffer("snooze_usd_20", 1)), "invalid fee")
    }

    @Test
    fun `the cap ends the ladder before max snoozes`() {
        val capped = FeeLadder { _, n -> if (n > 2) Outcome.Success(FeeStep.PriceCapReached) else UsdFeeLadder.productFor(1, n) }
        assertEquals("snooze_usd_02", capped.followingProduct(session(), SnoozeOffer("snooze_usd_01", 1)))
        assertNull(capped.followingProduct(session(), SnoozeOffer("snooze_usd_02", 2)))
    }

    @Test
    fun `the placeholder ports know nothing`() =
        runTest {
            assertNull(LivePriceSource.None.livePrice("snooze_usd_01"))
            assertNull(DisplayPrices.None.priceOf("snooze_usd_01"))
            assertNull(BillingCountry.None.countryCode())
        }

    @Test
    fun `the tax note follows the billing country`() {
        assertTrue(TaxNote.shows("US"))
        assertTrue(TaxNote.shows(" ca "))
        assertFalse(TaxNote.shows("FI"))
        assertFalse(TaxNote.shows(null))
    }
}

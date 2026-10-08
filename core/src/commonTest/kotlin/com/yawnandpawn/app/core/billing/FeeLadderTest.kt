package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SnoozeOffer
import com.yawnandpawn.app.core.session.ringSession
import com.yawnandpawn.app.core.session.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeeLadderTest {
    private fun product(tier: Int): Outcome<FeeStep, DomainError.InvalidFee> =
        Outcome.Success(FeeStep.Product("snooze_usd_" + tier.toString().padStart(2, '0'), tier))

    private val capped: Outcome<FeeStep, DomainError.InvalidFee> = Outcome.Success(FeeStep.PriceCapReached)

    private fun invalid(
        baseFeeTier: Int,
        snoozeNumber: Int,
    ): Outcome<FeeStep, DomainError.InvalidFee> = Outcome.Failure(DomainError.InvalidFee(baseFeeTier, snoozeNumber))

    /** (B, N) to the expected answer: the PRD §6.2 examples, the cap and every invalid input (spec edge-case matrix). */
    private val table: List<Triple<Int, Int, Outcome<FeeStep, DomainError.InvalidFee>>> =
        listOf(
            Triple(1, 1, product(1)),
            Triple(1, 2, product(2)),
            Triple(1, 3, product(3)),
            Triple(1, 4, product(4)),
            Triple(1, 5, product(5)),
            Triple(3, 1, product(3)),
            Triple(3, 2, product(6)),
            Triple(3, 3, product(9)),
            Triple(3, 4, product(12)),
            Triple(3, 5, product(15)),
            Triple(10, 5, product(50)),
            Triple(1, 50, product(50)),
            Triple(5, 10, product(50)),
            Triple(10, 6, capped),
            Triple(9, 6, capped),
            Triple(1, 51, capped),
            Triple(10, Int.MAX_VALUE, capped),
            Triple(0, 1, invalid(0, 1)),
            Triple(-1, 1, invalid(-1, 1)),
            Triple(11, 1, invalid(11, 1)),
            Triple(Int.MIN_VALUE, 1, invalid(Int.MIN_VALUE, 1)),
            Triple(1, 0, invalid(1, 0)),
            Triple(1, -3, invalid(1, -3)),
            Triple(11, 0, invalid(11, 0)),
            Triple(10, 0, invalid(10, 0)),
        )

    @Test
    fun `the ladder prices snooze N at base tier B as B times N, capped at 50, and rejects invalid input`() {
        table.forEach { (baseFeeTier, snoozeNumber, expected) ->
            assertEquals(expected, UsdFeeLadder.productFor(baseFeeTier, snoozeNumber), "B = $baseFeeTier, N = $snoozeNumber")
        }
    }

    @Test
    fun `every reachable combination has a product in the catalogue, 28 distinct`() {
        val reachable =
            FeeRules.BASE_FEE_TIERS.flatMap { b ->
                FeeRules.MAX_SNOOZES.map { n ->
                    val step = (UsdFeeLadder.productFor(b, n) as Outcome.Success).value
                    (step as FeeStep.Product).also { assertEquals(b * n, it.usdTier) }
                }
            }
        assertEquals(50, reachable.size)
        val ids = reachable.map { it.productId }.toSet()
        // The epic and PRD §6.3 say 31; B 1..10 times N 1..5 has 28 distinct products (spec, decision 7).
        assertEquals(28, ids.size)
        assertEquals(emptySet(), ids - SnoozeProducts.all.toSet())
    }

    @Test
    fun `the catalogue is the 50 USD tiers, cheapest first`() {
        assertEquals(50, SnoozeProducts.all.size)
        assertEquals("snooze_usd_01", SnoozeProducts.all.first())
        assertEquals("snooze_usd_10", SnoozeProducts.idOf(10))
        assertEquals("snooze_usd_50", SnoozeProducts.all.last())
        assertEquals((1..50).map(::product), SnoozeProducts.all.map { Outcome.Success(FeeStep.Product(it, it.takeLast(2).toInt())) })
    }

    @Test
    fun `the limits are base fee 1 to 10, max snoozes 1 to 5 and a 50 dollar cap`() {
        assertEquals(1..10, FeeRules.BASE_FEE_TIERS)
        assertEquals(1..5, FeeRules.MAX_SNOOZES)
        assertEquals(50, FeeRules.PRICE_CAP_TIER)
    }

    @Test
    fun `the next step is the ladder at the frozen base tier and snoozes granted plus one`() {
        val session = ringSession(testConfig().copy(baseFeeTier = 3)).copy(snoozesGranted = 2)
        assertEquals(product(9), UsdFeeLadder.nextStep(session))
        assertEquals(SnoozeOffer("snooze_usd_09", 3), UsdFeeLadder.nextOffer(session))
    }

    @Test
    fun `there is no next offer past the cap or with an invalid frozen fee`() {
        val capped = ringSession(testConfig().copy(baseFeeTier = 10)).copy(snoozesGranted = 5)
        assertEquals(this.capped, UsdFeeLadder.nextStep(capped))
        assertNull(UsdFeeLadder.nextOffer(capped))

        val broken = ringSession(testConfig().copy(baseFeeTier = 0))
        assertEquals(invalid(0, 1), UsdFeeLadder.nextStep(broken))
        assertNull(UsdFeeLadder.nextOffer(broken))
    }
}

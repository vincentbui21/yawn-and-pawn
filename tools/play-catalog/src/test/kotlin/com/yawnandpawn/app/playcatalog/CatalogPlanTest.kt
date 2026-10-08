package com.yawnandpawn.app.playcatalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogPlanTest {
    private fun plan(existing: List<OneTimeProduct>) = CatalogPlanner.plan(existing, TestCatalogs.conversions)

    private fun CatalogPlan.of(id: String) = products.single { it.productId == id }

    @Test
    fun `an empty catalogue creates all 50 products and activates them`() {
        val plan = plan(emptyList())

        assertEquals(50, plan.count(PlanKind.CREATE))
        assertEquals(50, plan.changes.size)
        assertEquals(SnoozeCatalog.productIds, plan.products.map { it.productId })
        plan.products.forEach {
            assertEquals(PatchField.entries.toSet(), it.patch)
            assertTrue(it.activate)
            assertEquals("2025/03", it.regionsVersion)
        }
        assertEquals("USD 12.00, 3 regions", plan.of("snooze_usd_12").reasons.single())
        assertEquals("50 create, 0 update, 0 activate, 0 unchanged, 0 unmanaged, 0 attention. 50 changes.", plan.summary())
    }

    @Test
    fun `the partial catalogue creates 40, patches 2 prices, reactivates 1 and leaves the rest`() {
        val plan = plan(TestCatalogs.partial())

        assertEquals(40, plan.count(PlanKind.CREATE))
        assertEquals(listOf("snooze_usd_02", "snooze_usd_03"), plan.products.filter { it.kind == PlanKind.UPDATE }.map { it.productId })
        assertEquals(listOf("snooze_usd_04"), plan.products.filter { it.kind == PlanKind.ACTIVATE }.map { it.productId })
        assertEquals(7, plan.count(PlanKind.UNCHANGED))
        assertEquals(listOf("spike_s1_test"), plan.products.filter { it.kind == PlanKind.UNMANAGED }.map { it.productId })
        assertEquals("40 create, 2 update, 1 activate, 7 unchanged, 1 unmanaged, 0 attention. 43 changes.", plan.summary())
    }

    @Test
    fun `a wrong price patches only the purchase options and names the prices`() {
        val update = plan(TestCatalogs.partial()).of("snooze_usd_02")

        assertEquals(setOf(PatchField.PURCHASE_OPTIONS), update.patch)
        assertFalse(update.activate)
        assertEquals(listOf("price USD 3.00 -> USD 2.00"), update.reasons)
        assertEquals(
            Price.usd(2),
            update.desired!!
                .purchaseOptions
                .single()
                .regions
                .getValue("US")
                .price,
        )
    }

    @Test
    fun `an inactive product is reactivated without a patch`() {
        val activate = plan(TestCatalogs.partial()).of("snooze_usd_04")

        assertTrue(activate.patch.isEmpty())
        assertTrue(activate.activate)
        assertEquals(listOf("inactive; reactivate"), activate.reasons)
    }

    @Test
    fun `a draft option left by a failed run is activated`() {
        val activate = plan(listOf(TestCatalogs.inState(5, OptionState.DRAFT))).of("snooze_usd_05")

        assertEquals(PlanKind.ACTIVATE, activate.kind)
        assertEquals(listOf("not active yet"), activate.reasons)
    }

    @Test
    fun `a wrong listing patches only the listings`() {
        val typo = TestCatalogs.applied(6).copy(listings = listOf(Listing("en-US", "Snoze", "One snooze for your alarm.")))

        val update = plan(listOf(typo)).of("snooze_usd_06")

        assertEquals(PlanKind.UPDATE, update.kind)
        assertEquals(setOf(PatchField.LISTINGS), update.patch)
        assertEquals(listOf("listing en-US differs"), update.reasons)
    }

    @Test
    fun `a region Play added since the last run, or an unavailable one, is an update`() {
        val missing = TestCatalogs.withBuyOption(TestCatalogs.applied(8)) { it.copy(regions = it.regions - "JP") }
        val unavailable =
            TestCatalogs.withBuyOption(TestCatalogs.applied(9)) {
                it.copy(regions = it.regions + ("FI" to it.regions.getValue("FI").copy(available = false)))
            }

        val plan = plan(listOf(missing, unavailable))

        assertEquals(listOf("1 regions missing or unavailable (JP)"), plan.of("snooze_usd_08").reasons)
        assertEquals(listOf("1 regions missing or unavailable (FI)"), plan.of("snooze_usd_09").reasons)
        assertEquals(setOf(PatchField.PURCHASE_OPTIONS), plan.of("snooze_usd_09").patch)
    }

    @Test
    fun `local prices that moved with exchange rates are not an update`() {
        val drifted =
            TestCatalogs.withBuyOption(TestCatalogs.applied(10)) {
                it.copy(regions = it.regions + ("JP" to RegionalConfig(Price("JPY", 1490), available = true)))
            }

        assertEquals(PlanKind.UNCHANGED, plan(listOf(drifted)).of("snooze_usd_10").kind)
    }

    @Test
    fun `a buy option that is not legacy compatible, or a wrong new-regions price, is an update`() {
        val notLegacy = TestCatalogs.withBuyOption(TestCatalogs.applied(11)) { it.copy(legacyCompatible = false) }
        val newRegions = TestCatalogs.withBuyOption(TestCatalogs.applied(12)) { it.copy(newRegionsUsd = Price.usd(1)) }

        val plan = plan(listOf(notLegacy, newRegions))

        assertEquals(listOf("purchase option is not a legacy-compatible single-quantity Buy option"), plan.of("snooze_usd_11").reasons)
        assertEquals(listOf("new-regions price USD 1.00 -> USD 12.00"), plan.of("snooze_usd_12").reasons)
    }

    @Test
    fun `a product without the buy option gets it and activates it`() {
        val empty = TestCatalogs.applied(13).copy(purchaseOptions = emptyList())

        val update = plan(listOf(empty)).of("snooze_usd_13")

        assertEquals(PlanKind.UPDATE, update.kind)
        assertEquals(setOf(PatchField.PURCHASE_OPTIONS), update.patch)
        assertTrue(update.activate)
        assertEquals(listOf("purchase option buy missing", "not active yet"), update.reasons)
    }

    @Test
    fun `a product with an extra purchase option is flagged and never written`() {
        val extra =
            TestCatalogs.applied(14).let { product ->
                product.copy(purchaseOptions = product.purchaseOptions + product.purchaseOptions.single().copy(id = "rent", isBuy = false))
            }

        val attention = plan(listOf(extra)).of("snooze_usd_14")

        assertEquals(PlanKind.ATTENTION, attention.kind)
        assertFalse(attention.isChange)
        assertTrue(attention.patch.isEmpty())
        assertFalse(attention.activate)
    }

    @Test
    fun `products outside the 50 ids are unmanaged, sorted, and never changed`() {
        val other = TestCatalogs.applied(1).copy(productId = "snooze_usd_51")

        val plan = plan(listOf(FakePlayCatalogApi.spikeProduct, other))

        val unmanaged = plan.products.filter { it.kind == PlanKind.UNMANAGED }
        assertEquals(listOf("snooze_usd_51", "spike_s1_test"), unmanaged.map { it.productId })
        unmanaged.forEach {
            assertFalse(it.isChange)
            assertTrue(it.patch.isEmpty())
            assertFalse(it.activate)
        }
    }

    @Test
    fun `the applied catalogue is unchanged`() {
        val plan = plan((1..50).map(TestCatalogs::applied))

        assertEquals(50, plan.count(PlanKind.UNCHANGED))
        assertTrue(plan.changes.isEmpty())
        assertTrue(plan.summary().endsWith(". 0 changes."))
    }
}

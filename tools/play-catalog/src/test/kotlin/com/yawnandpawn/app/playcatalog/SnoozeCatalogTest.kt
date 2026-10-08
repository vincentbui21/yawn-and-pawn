package com.yawnandpawn.app.playcatalog

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnoozeCatalogTest {
    private val repoRoot = File(checkNotNull(System.getProperty("yawnandpawn.repoRoot")) { "run through Gradle" })

    @Test
    fun `there are exactly 50 ids, snooze_usd_01 to snooze_usd_50, in order`() {
        val ids = SnoozeCatalog.productIds

        assertEquals(50, ids.size)
        assertEquals(50, ids.toSet().size)
        assertEquals("snooze_usd_01", ids.first())
        assertEquals("snooze_usd_09", ids[8])
        assertEquals("snooze_usd_10", ids[9])
        assertEquals("snooze_usd_50", ids.last())
        ids.forEachIndexed { index, id -> assertEquals("snooze_usd_" + (index + 1).toString().padStart(2, '0'), id) }
    }

    @Test
    fun `product NN costs NN dollars as its base price`() {
        (1..50).forEach { tier ->
            assertEquals(Price("USD", tier.toLong(), 0), SnoozeCatalog.basePrice(tier))
            assertEquals(tier, SnoozeCatalog.tierOf(SnoozeCatalog.productId(tier)))
        }
        assertEquals("USD 1.00", SnoozeCatalog.basePrice(1).toString())
        assertEquals("USD 50.00", SnoozeCatalog.basePrice(50).toString())
    }

    @Test
    fun `the shared id list in config matches the catalogue`() {
        val listed =
            repoRoot
                .resolve("config/snooze-products.txt")
                .readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }

        assertEquals(SnoozeCatalog.productIds, listed)
    }

    @Test
    fun `ids outside the managed pattern are not managed`() {
        listOf("spike_s1_test", "snooze_usd_00", "snooze_usd_51", "snooze_usd_1", "snooze_usd_001", "Snooze_usd_01", "snooze_eur_01")
            .forEach { assertNull(SnoozeCatalog.tierOf(it), it) }
        assertFailsWith<IllegalArgumentException> { SnoozeCatalog.productId(0) }
        assertFailsWith<IllegalArgumentException> { SnoozeCatalog.productId(51) }
    }

    @Test
    fun `the desired product has the listing, one legacy-compatible buy option and every region priced`() {
        val converted = FakePlayCatalogApi.convert(7)

        val product = SnoozeCatalog.desiredProduct(7, converted)

        assertEquals("snooze_usd_07", product.productId)
        assertEquals(listOf(Listing("en-US", "Snooze", "One snooze for your alarm.")), product.listings)
        val option = product.purchaseOptions.single()
        assertEquals("buy", option.id)
        assertTrue(option.isBuy && option.legacyCompatible && !option.multiQuantityEnabled)
        assertNull(option.state, "the state is output only and never written")
        assertEquals(converted.regions.keys, option.regions.keys)
        assertTrue(option.regions.values.all { it.available })
        assertEquals(Price.usd(7), option.regions.getValue("US").price)
        assertEquals(Price("JPY", 1050), option.regions.getValue("JP").price)
        assertEquals(Price.usd(7), option.newRegionsUsd)
        assertTrue(option.newRegionsAvailable)
    }

    @Test
    fun `the US price is pinned to the base price and other-language listings are kept`() {
        val converted = FakePlayCatalogApi.convert(3).let { it.copy(regions = it.regions + ("US" to Price("USD", 2, 990_000_000))) }
        val german = Listing("de-DE", "Schlummern", "Einmal schlummern.")
        val existing = OneTimeProduct("snooze_usd_03", listOf(Listing("en-US", "Old", "Old"), german), emptyList())

        val product = SnoozeCatalog.desiredProduct(3, converted, existing)

        assertEquals(
            Price.usd(3),
            product.purchaseOptions
                .single()
                .regions
                .getValue("US")
                .price,
        )
        assertEquals(listOf(SnoozeCatalog.listing, german), product.listings)
    }

    @Test
    fun `prices print with their currency and two or more decimals`() {
        assertEquals("EUR 0.90", Price("EUR", 0, 900_000_000).toString())
        assertEquals("JPY 150.00", Price("JPY", 150).toString())
        assertEquals("USD 0.495", Price("USD", 0, 495_000_000).toString())
    }
}

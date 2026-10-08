package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.alarm.RecordingLogger
import com.yawnandpawn.app.core.alarm.TestClock
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Story 4.3: the price snapshot, its merge and staleness rules, the stored form and [CachedPriceCatalog]. */
class PriceCatalogTest {
    private val t0 = Instant.parse("2027-03-03T06:00:00Z")
    private val t1 = Instant.parse("2027-03-04T06:00:00Z")
    private val ids = SnoozeProducts.all

    private fun price(
        tier: Int,
        currency: String = "USD",
        text: String = "$currency $tier.00",
        micros: Long = tier * Money.MICROS_PER_UNIT,
    ) = ProductPrice(SnoozeProducts.idOf(tier), text, Money(micros, currency))

    private fun entry(
        tier: Int,
        at: Instant = t0,
        currency: String = "USD",
    ) = price(tier, currency).let { PriceEntry(it.productId, it.formattedPrice, it.price, at) }

    private fun snapshot(
        tiers: IntRange,
        at: Instant = t0,
        currency: String = "USD",
    ) = PriceCatalogSnapshot(tiers.map { entry(it, at, currency) }.associateBy { it.productId })

    private fun result(
        prices: List<ProductPrice>,
        unfetched: Set<String> = emptySet(),
    ) = ProductDetailsResult(prices, unfetched)

    // --- Snapshot -------------------------------------------------------------------------------------------------

    @Test
    fun `priceFor returns the entry or null`() {
        val snapshot = snapshot(1..2)

        assertEquals(entry(1), snapshot.priceFor("snooze_usd_01"))
        assertNull(snapshot.priceFor("snooze_usd_03"))
        assertNull(PriceCatalogSnapshot.EMPTY.priceFor("snooze_usd_01"))
    }

    @Test
    fun `freshness follows the age of the fetch`() {
        val table: List<Pair<Duration, PriceFreshness>> =
            listOf(
                Duration.ZERO to PriceFreshness.Fresh,
                24.hours to PriceFreshness.Fresh,
                24.hours + 1.milliseconds to PriceFreshness.Stale,
                30.days to PriceFreshness.Stale,
                30.days + 1.milliseconds to PriceFreshness.Expired,
                (-1).hours to PriceFreshness.Fresh,
            )
        table.forEach { (age, expected) -> assertEquals(expected, entry(1).freshnessAt(t0 + age), "age $age") }
    }

    @Test
    fun `an expired price is not displayable, a stale one is`() {
        val snapshot = snapshot(1..1)

        assertEquals(entry(1), snapshot.displayablePriceFor("snooze_usd_01", t0 + 2.days))
        assertNull(snapshot.displayablePriceFor("snooze_usd_01", t0 + 31.days))
        assertNull(snapshot.displayablePriceFor("snooze_usd_02", t0))
    }

    // --- Merge ----------------------------------------------------------------------------------------------------

    @Test
    fun `a full answer replaces every entry with the new fetch time`() {
        val merged = snapshot(1..50).mergedWith(result((1..50).map { price(it) }), ids, t1)

        assertEquals(snapshot(1..50, t1), merged)
    }

    @Test
    fun `a partial answer keeps the previous entries of the products it lacks`() {
        val unfetched = (11..50).map { SnoozeProducts.idOf(it) }.toSet()
        val merged = snapshot(1..50).mergedWith(result((1..10).map { price(it) }, unfetched), ids, t1)

        assertEquals(PriceCatalogSnapshot(snapshot(1..10, t1).entries + snapshot(11..50).entries), merged)
    }

    @Test
    fun `products Play reports as unfetched are ignored even when a price came back`() {
        val merged = snapshot(5..5).mergedWith(result(listOf(price(5, text = "USD 9.99")), setOf("snooze_usd_05")), ids, t1)

        assertEquals(snapshot(5..5), merged)
    }

    @Test
    fun `a new currency drops the entries of the old one`() {
        val merged = snapshot(1..50).mergedWith(result((1..10).map { price(it, "EUR") }), ids, t1)

        assertEquals(snapshot(1..10, t1, "EUR"), merged)
    }

    @Test
    fun `blank strings, zero or negative prices and unknown products are ignored`() {
        val bogus =
            listOf(
                price(1, text = " "),
                price(2, micros = 0),
                price(3, micros = -1),
                ProductPrice("spike_s1_test", "USD 1.00", Money.of(1, "USD")),
            )
        val merged = snapshot(1..3).mergedWith(result(bogus), ids, t1)

        assertEquals(snapshot(1..3), merged)
    }

    @Test
    fun `an answer with nothing usable keeps every requested entry and drops entries no longer requested`() {
        val previous = PriceCatalogSnapshot(snapshot(1..2).entries + ("retired" to entry(3).copy(productId = "retired")))
        val merged = previous.mergedWith(result(emptyList()), ids, t1)

        assertEquals(snapshot(1..2), merged)
    }

    // --- Stored form ----------------------------------------------------------------------------------------------

    @Test
    fun `the stored form round-trips every field`() {
        val snapshot = PriceCatalogSnapshot(snapshot(1..2).entries + ("snooze_usd_03" to entry(3, t1, "JPY")))

        assertEquals(snapshot, PriceCatalogJson.decode(PriceCatalogJson.encode(snapshot)))
        assertEquals(PriceCatalogSnapshot.EMPTY, PriceCatalogJson.decode(PriceCatalogJson.encode(PriceCatalogSnapshot.EMPTY)))
    }

    @Test
    fun `text that is not the stored form decodes to null`() {
        listOf("{not json", "", "[]", """{"version":2,"entries":[]}""", """{"entries":[]}""").forEach {
            assertNull(PriceCatalogJson.decode(it), it)
        }
    }

    @Test
    fun `an entry with a malformed currency is dropped and the rest kept, unknown keys are ignored`() {
        val text =
            """{"version":1,"later":true,"entries":[""" +
                """{"productId":"snooze_usd_01","formattedPrice":"US$ 1","micros":1000000,"currency":"usd","fetchedAt":0},""" +
                """{"productId":"snooze_usd_02","formattedPrice":"US$ 2","micros":2000000,"currency":"USD","fetchedAt":0,"x":1}]}"""

        val decoded = PriceCatalogJson.decode(text)

        assertEquals(
            PriceCatalogSnapshot(
                mapOf("snooze_usd_02" to PriceEntry("snooze_usd_02", "US$ 2", Money.of(2, "USD"), Instant.fromEpochMilliseconds(0))),
            ),
            decoded,
        )
    }

    // --- CachedPriceCatalog ---------------------------------------------------------------------------------------

    private class Source(
        var result: Outcome<ProductDetailsResult, DomainError>,
    ) : ProductDetailsSource {
        val requests = mutableListOf<List<String>>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun fetch(productIds: List<String>): Outcome<ProductDetailsResult, DomainError> {
            requests += productIds
            gate?.await()
            return result
        }
    }

    private class Store(
        initial: PriceCatalogSnapshot,
    ) : PriceCacheStore {
        val state = MutableStateFlow(initial)
        var failure: DomainError? = null
        var updates = 0

        override fun observe(): Flow<PriceCatalogSnapshot> = state

        override suspend fun update(transform: (PriceCatalogSnapshot) -> PriceCatalogSnapshot): Outcome<PriceCatalogSnapshot, DomainError> {
            updates++
            failure?.let { return Outcome.Failure(it) }
            state.value = transform(state.value)
            return Outcome.Success(state.value)
        }
    }

    private val logger = RecordingLogger()
    private val clock = TestClock(t1)

    @Test
    fun `refresh fetches all 50 products and stores the merged snapshot`() =
        runTest {
            val source = Source(Outcome.Success(result((1..50).map { price(it) })))
            val store = Store(PriceCatalogSnapshot.EMPTY)
            val catalog = CachedPriceCatalog(source, store, clock, logger)

            assertEquals(Outcome.Success(Unit), catalog.refresh())

            assertEquals(listOf(ids), source.requests)
            assertEquals(snapshot(1..50, t1), catalog.observe().first())
            assertEquals(emptyList(), logger.events)
        }

    @Test
    fun `a failed fetch keeps the previous snapshot, is logged and returned`() =
        runTest {
            val offline = DomainError.ProductDetailsFailed(transient = true, cause = "offline")
            val store = Store(snapshot(1..50))
            val catalog = CachedPriceCatalog(Source(Outcome.Failure(offline)), store, clock, logger)

            assertEquals(Outcome.Failure(offline), catalog.refresh())

            assertEquals(snapshot(1..50), store.state.value)
            assertEquals(0, store.updates)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed.of(CachedPriceCatalog.FETCH_PRICES, offline)), logger.events)
        }

    @Test
    fun `a failed write is logged and returned`() =
        runTest {
            val disk = DomainError.StorageFailure("disk full")
            val store = Store(snapshot(1..1)).apply { failure = disk }
            val catalog = CachedPriceCatalog(Source(Outcome.Success(result((1..50).map { price(it) }))), store, clock, logger)

            assertEquals(Outcome.Failure(disk), catalog.refresh())

            assertEquals(snapshot(1..1), store.state.value)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed.of(CachedPriceCatalog.STORE_PRICES, disk)), logger.events)
        }

    @Test
    fun `a partial refresh keeps the entries Play did not return`() =
        runTest {
            val unfetched = (11..50).map { SnoozeProducts.idOf(it) }.toSet()
            val store = Store(snapshot(1..50))
            val catalog = CachedPriceCatalog(Source(Outcome.Success(result((1..10).map { price(it) }, unfetched))), store, clock, logger)

            catalog.refresh()

            assertEquals(PriceCatalogSnapshot(snapshot(1..10, t1).entries + snapshot(11..50).entries), store.state.value)
        }

    @Test
    fun `one refresh runs at a time, and the cache is readable while Play is slow`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val source = Source(Outcome.Success(result((1..50).map { price(it) }))).apply { this.gate = gate }
            val store = Store(snapshot(1..50))
            val catalog = CachedPriceCatalog(source, store, clock, logger)

            val first = async { catalog.refresh() }
            val second = async { catalog.refresh() }
            runCurrent()

            assertEquals(1, source.requests.size, "the second refresh waits for the first")
            assertEquals(snapshot(1..50), catalog.observe().first(), "the cached snapshot is there at once")

            gate.complete(Unit)
            assertEquals(Outcome.Success(Unit), first.await())
            assertEquals(Outcome.Success(Unit), second.await())
            assertEquals(2, source.requests.size)
            assertEquals(2, store.updates)
        }

    @Test
    fun `observe is the store's flow`() {
        val store = Store(PriceCatalogSnapshot.EMPTY)

        assertSame(store.state, CachedPriceCatalog(Source(Outcome.Success(result(emptyList()))), store, clock, logger).observe())
    }
}

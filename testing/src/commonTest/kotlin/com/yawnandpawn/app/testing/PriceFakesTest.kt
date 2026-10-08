package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.billing.ProductDetailsResult
import com.yawnandpawn.app.core.billing.SnoozeProducts
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.work.BackgroundJob
import com.yawnandpawn.app.core.work.BackgroundTaskKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PriceFakesTest {
    @Test
    fun `the builders price tier N at N whole units in a fixed form`() {
        val price = aProductPrice(7, "EUR")

        assertEquals("snooze_usd_07", price.productId)
        assertEquals("EUR 7.00", price.formattedPrice)
        assertEquals(Money.of(7, "EUR"), price.price)
        assertEquals(SnoozeProducts.all.size, productPrices().size)
        assertEquals(aPriceEntry(3), aPriceSnapshot(3..3).priceFor("snooze_usd_03"))
        assertEquals(DEFAULT_FAKE_INSTANT, aPriceEntry(1).fetchedAt)
    }

    @Test
    fun `the product details source answers what it is told, waits at its gate and records requests`() =
        runTest {
            val source = FakeProductDetailsSource()
            assertEquals(Outcome.Success(ProductDetailsResult(productPrices())), source.fetch(listOf("a")))

            source.succeedWith(productPrices(1..2), setOf("snooze_usd_03"))
            assertEquals(Outcome.Success(ProductDetailsResult(productPrices(1..2), setOf("snooze_usd_03"))), source.fetch(listOf("b")))
            source.failWith()
            assertIs<DomainError.ProductDetailsFailed>(assertIs<Outcome.Failure<DomainError>>(source.fetch(listOf("c"))).error)

            val gate = CompletableDeferred<Unit>()
            source.gate = gate
            val waiting = async { source.fetch(listOf("d")) }
            runCurrent()
            assertEquals(3, source.completed)
            gate.complete(Unit)
            waiting.await()

            assertEquals(4, source.completed)
            assertEquals(listOf(listOf("a"), listOf("b"), listOf("c"), listOf("d")), source.requests)
        }

    @Test
    fun `the cache store updates in memory and fails without changing when told`() =
        runTest {
            val store = FakePriceCacheStore()
            assertEquals(Outcome.Success(aPriceSnapshot(1..1)), store.update { aPriceSnapshot(1..1) })
            assertEquals(aPriceSnapshot(1..1), store.observe().first())

            store.failure = DomainError.StorageFailure("disk")
            assertIs<Outcome.Failure<DomainError>>(store.update { PriceCatalogSnapshot.EMPTY })
            assertEquals(aPriceSnapshot(1..1), store.snapshot)
        }

    @Test
    fun `the catalog counts refreshes and applies the snapshot set for after a successful one`() =
        runTest {
            val catalog = FakePriceCatalog(aPriceSnapshot(1..1))
            catalog.afterRefresh = aPriceSnapshot(1..2)

            catalog.refreshResult = Outcome.Failure(DomainError.StorageFailure("x"))
            catalog.refresh()
            assertEquals(aPriceSnapshot(1..1), catalog.observe().first())

            catalog.refreshResult = Outcome.Success(Unit)
            catalog.refresh()
            assertEquals(aPriceSnapshot(1..2), catalog.snapshot)
            assertEquals(2, catalog.refreshes)

            catalog.snapshot = PriceCatalogSnapshot.EMPTY
            assertEquals(PriceCatalogSnapshot.EMPTY, catalog.observe().first())
        }

    @Test
    fun `the background work records jobs and refuses them when told`() {
        val work = FakeBackgroundWork()
        val job = BackgroundJob("j", BackgroundTaskKind.PriceRefresh, needsNetwork = false)

        assertEquals(Outcome.Success(Unit), work.enqueue(job))
        work.failure = DomainError.BackgroundWorkFailure("locked")
        assertEquals(Outcome.Failure(DomainError.BackgroundWorkFailure("locked")), work.enqueue(job))

        assertEquals(listOf(job), work.enqueued)
    }
}

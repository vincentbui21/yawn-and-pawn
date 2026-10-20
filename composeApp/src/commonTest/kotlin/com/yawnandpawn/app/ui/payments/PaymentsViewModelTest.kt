package com.yawnandpawn.app.ui.payments

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceCachePolicy
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.billing.PriceEntry
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.testing.DEFAULT_FAKE_INSTANT
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakePriceCatalog
import com.yawnandpawn.app.testing.aPriceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Story 5.8 review fix 2: "How payments & refunds work" states the cap as Google Play's cached local price of the top
 * tier (`snooze_usd_50`), and nothing (so the screen names the $50 tier) while that price is unknown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock()
    private val logger = FakeLogger()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The cap [catalog] gives, read while the screen collects it. */
    private fun TestScope.capOf(catalog: PriceCatalog): String? {
        val viewModel = PaymentsViewModel(catalog, clock, logger)
        backgroundScope.launch { viewModel.cap.collect {} }
        return viewModel.cap.value
    }

    private fun capPrice(fetchedAt: Instant = DEFAULT_FAKE_INSTANT): PriceCatalogSnapshot =
        PriceCatalogSnapshot(
            mapOf(
                PaymentsViewModel.CAP_PRODUCT to
                    PriceEntry(PaymentsViewModel.CAP_PRODUCT, "€59.99", Money(59_990_000, "EUR"), fetchedAt),
            ),
        )

    @Test
    fun `the cap is Play's own cached price of snooze_usd_50, stale too`() =
        runTest(dispatcher) {
            assertEquals("snooze_usd_50", PaymentsViewModel.CAP_PRODUCT)
            assertEquals("€59.99", capOf(FakePriceCatalog(capPrice())))
            assertEquals("€59.99", capOf(FakePriceCatalog(capPrice(fetchedAt = DEFAULT_FAKE_INSTANT - 2.days))))
        }

    @Test
    fun `with no cached price of the top tier, or an expired one, the cap is unknown`() =
        runTest(dispatcher) {
            assertNull(capOf(FakePriceCatalog()))
            assertNull(capOf(FakePriceCatalog(aPriceSnapshot(tiers = 1..49))))
            val expired = DEFAULT_FAKE_INSTANT - PriceCachePolicy.EXPIRES_AFTER - 1.days
            assertNull(capOf(FakePriceCatalog(capPrice(fetchedAt = expired))))
        }

    @Test
    fun `a price that arrives later replaces the unknown cap`() =
        runTest(dispatcher) {
            val catalog = FakePriceCatalog()
            val viewModel = PaymentsViewModel(catalog, clock, logger)
            backgroundScope.launch { viewModel.cap.collect {} }
            assertNull(viewModel.cap.value)

            catalog.snapshot = capPrice()

            assertEquals("€59.99", viewModel.cap.value)
        }

    @Test
    fun `an unreadable price cache is logged and the cap is unknown`() =
        runTest(dispatcher) {
            val broken =
                object : PriceCatalog {
                    override fun observe(): Flow<PriceCatalogSnapshot> = flow { error("disk gone") }

                    override suspend fun refresh(): Outcome<Unit, DomainError> = Outcome.Success(Unit)
                }

            assertNull(capOf(broken))
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed(PaymentsViewModel.LOAD_CAP_PRICE, "disk gone")), logger.events)
        }
}

package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.alarm.RecordingLogger
import com.yawnandpawn.app.core.alarm.TestClock
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.net.Connectivity
import com.yawnandpawn.app.core.session.CameraFallbackPolicy
import com.yawnandpawn.app.core.session.PluginCheckValidator
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionReducer
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.SnoozeOffer
import com.yawnandpawn.app.core.session.T0
import com.yawnandpawn.app.core.session.UnavailableReason
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.session.ringSession
import com.yawnandpawn.app.core.session.testConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** A connectivity the test switches: [online] emits to every collector (core cannot use :testing's fake, AD-1). */
internal class TestConnectivity(
    online: Boolean,
) : Connectivity {
    private val state = MutableStateFlow(online)

    var online: Boolean
        get() = state.value
        set(value) {
            state.value = value
        }

    override fun observeOnline(): Flow<Boolean> = state
}

/** Story 4.7: every reason of `snoozeAvailability`, its order, the live env and the policy behind the reducer. */
class SnoozeAvailabilityTest {
    private val now = Instant.parse("2027-03-03T06:00:00Z")
    private val logger = RecordingLogger()

    private fun price(
        tier: Int,
        fetchedAt: Instant = now,
    ): PriceEntry = PriceEntry(SnoozeProducts.idOf(tier), "US$$tier.00", Money.of(tier, "USD"), fetchedAt)

    private val allPrices = PriceCatalogSnapshot((1..50).map { price(it) }.associateBy { it.productId })

    private fun env(
        online: Boolean = true,
        prices: PriceCatalogSnapshot = allPrices,
        userUnlocked: Boolean = true,
        stranded: Set<String> = emptySet(),
    ) = SnoozeEnv(online, prices, now, userUnlocked, stranded)

    private fun session(
        testMode: Boolean = false,
        baseFeeTier: Int = 1,
        maxSnoozes: Int = 5,
        granted: Int = 0,
        pending: Boolean = false,
        declined: String? = null,
    ): SessionData =
        ringSession(testConfig(testMode = testMode).copy(baseFeeTier = baseFeeTier, maxSnoozes = maxSnoozes)).copy(
            snoozesGranted = granted,
            paymentPending = pending,
            declinedReuseProduct = declined,
        )

    private fun unavailable(
        reason: UnavailableReason,
        price: PriceEntry? = null,
    ) = SnoozeAvailability.Unavailable(reason, price)

    private fun available(
        tier: Int,
        snoozeNumber: Int,
    ) = SnoozeAvailability.Available(SnoozeOffer(SnoozeProducts.idOf(tier), snoozeNumber, price(tier)))

    private val p01 = SnoozeProducts.idOf(1)

    /** One row per reason, the overlaps (the earlier reason wins) and Available (spec: reasons table and matrix). */
    private val table: List<Triple<String, Pair<SessionData, SnoozeEnv>, SnoozeAvailability>> =
        listOf(
            Triple("available", session() to env(), available(1, 1)),
            Triple("third snooze at B = 3", session(baseFeeTier = 3, granted = 2) to env(), available(9, 3)),
            Triple("test mode", session(testMode = true) to env(), unavailable(UnavailableReason.TestMode)),
            Triple("test mode and offline", session(testMode = true) to env(online = false), unavailable(UnavailableReason.TestMode)),
            Triple(
                "test mode and locked",
                session(testMode = true) to env(userUnlocked = false),
                unavailable(UnavailableReason.TestMode),
            ),
            Triple("locked", session() to env(userUnlocked = false), unavailable(UnavailableReason.BeforeFirstUnlock)),
            Triple(
                "locked, max snoozes and offline",
                session(granted = 5) to env(userUnlocked = false, online = false),
                unavailable(UnavailableReason.BeforeFirstUnlock),
            ),
            Triple("max snoozes", session(granted = 5) to env(), unavailable(UnavailableReason.MaxSnoozesReached)),
            Triple(
                "max snoozes and offline",
                session(granted = 5) to env(online = false),
                unavailable(UnavailableReason.MaxSnoozesReached),
            ),
            Triple(
                "max 1 after one snooze",
                session(maxSnoozes = 1, granted = 1) to env(),
                unavailable(UnavailableReason.MaxSnoozesReached),
            ),
            // Production limits (B ≤ 10, max ≤ 5) never reach the cap: a directly built config (B = 10, max 6).
            Triple(
                "price cap, B = 10, max 6",
                session(baseFeeTier = 10, maxSnoozes = 6, granted = 5) to env(),
                unavailable(UnavailableReason.PriceCapReached),
            ),
            Triple(
                "price cap and pending",
                session(baseFeeTier = 10, maxSnoozes = 6, granted = 5, pending = true) to env(online = false),
                unavailable(UnavailableReason.PriceCapReached),
            ),
            Triple(
                "max snoozes before the cap: B = 10, max 5, 5 granted",
                session(baseFeeTier = 10, granted = 5) to env(),
                unavailable(UnavailableReason.MaxSnoozesReached),
            ),
            Triple("invalid frozen fee", session(baseFeeTier = 0) to env(), unavailable(UnavailableReason.InvalidFee)),
            Triple(
                "invalid fee before pending and offline",
                session(baseFeeTier = 0, pending = true) to env(online = false),
                unavailable(UnavailableReason.InvalidFee),
            ),
            Triple(
                "test mode before an invalid fee",
                session(baseFeeTier = 0, testMode = true) to env(),
                unavailable(UnavailableReason.TestMode),
            ),
            Triple("pending", session(pending = true) to env(), unavailable(UnavailableReason.PaymentPending)),
            Triple(
                "pending, refunding and offline",
                session(pending = true, declined = p01) to env(online = false, stranded = setOf(p01)),
                unavailable(UnavailableReason.PaymentPending),
            ),
            Triple(
                "refunding",
                session(declined = p01) to env(stranded = setOf(p01)),
                unavailable(UnavailableReason.EarlierPaymentRefunding, price(1)),
            ),
            Triple(
                "refunding and offline",
                session(declined = p01) to env(online = false, stranded = setOf(p01)),
                unavailable(UnavailableReason.EarlierPaymentRefunding, price(1)),
            ),
            Triple(
                "refunding with no cached price",
                session(declined = p01) to env(prices = PriceCatalogSnapshot.EMPTY, stranded = setOf(p01)),
                unavailable(UnavailableReason.EarlierPaymentRefunding),
            ),
            Triple("declined, the stranded payment cleared", session(declined = p01) to env(), available(1, 1)),
            Triple(
                "declined another product",
                session(declined = SnoozeProducts.idOf(2)) to env(stranded = setOf(SnoozeProducts.idOf(2))),
                available(1, 1),
            ),
            Triple("stranded, not declined: offered on Pay", session() to env(stranded = setOf(p01)), available(1, 1)),
            Triple("offline", session() to env(online = false), unavailable(UnavailableReason.Offline)),
            Triple(
                "offline with no cache",
                session() to env(online = false, prices = PriceCatalogSnapshot.EMPTY),
                unavailable(UnavailableReason.Offline),
            ),
            Triple(
                "no cache",
                session() to env(prices = PriceCatalogSnapshot.EMPTY),
                unavailable(UnavailableReason.CatalogueNotLoaded),
            ),
            Triple(
                "only another product cached",
                session() to env(prices = PriceCatalogSnapshot(mapOf(SnoozeProducts.idOf(2) to price(2)))),
                unavailable(UnavailableReason.CatalogueNotLoaded),
            ),
            Triple(
                "expired price",
                session() to env(prices = PriceCatalogSnapshot(mapOf(p01 to price(1, now - 31.days)))),
                unavailable(UnavailableReason.CatalogueNotLoaded),
            ),
        )

    @Test
    fun `every reason in its order, the overlaps and the available case`() {
        table.forEach { (name, input, expected) ->
            assertEquals(expected, snoozeAvailability(input.first, input.second, UsdFeeLadder, logger), name)
        }
    }

    @Test
    fun `a stale price is still shown, Play's own string included`() {
        val stale = price(1, now - 2.days)
        val result = snoozeAvailability(session(), env(prices = PriceCatalogSnapshot(mapOf(p01 to stale))), UsdFeeLadder, logger)

        assertEquals(SnoozeAvailability.Available(SnoozeOffer(p01, 1, stale)), result)
        assertEquals("US$1.00", (result as SnoozeAvailability.Available).offer.price?.formattedPrice)
    }

    @Test
    fun `an invalid frozen fee is logged with its diagnostic, the other reasons are not`() {
        snoozeAvailability(session(baseFeeTier = 11), env(), UsdFeeLadder, logger)
        snoozeAvailability(session(), env(online = false), UsdFeeLadder, logger)

        assertEquals(
            listOf<LogEvent>(LogEvent.OperationFailed.of(PRICE_NEXT_SNOOZE, DomainError.InvalidFee(11, 1))),
            logger.events,
        )
    }

    @Test
    fun `the ladder is the injected one`() {
        val ladder = FeeLadder { _, _ -> Outcome.Success(FeeStep.Product(SnoozeProducts.idOf(7), 7)) }

        assertEquals(available(7, 1), snoozeAvailability(session(), env(), ladder, logger))
    }

    private class Lock(
        unlocked: Boolean,
    ) : UserLockState {
        val state = MutableStateFlow(unlocked)

        override fun isUserUnlocked(): Boolean = state.value

        override fun observe(): Flow<Boolean> = state
    }

    private class Prices(
        snapshot: PriceCatalogSnapshot,
    ) : PriceCatalog {
        val state = MutableStateFlow(snapshot)

        override fun observe(): Flow<PriceCatalogSnapshot> = state

        override suspend fun refresh(): Outcome<Unit, DomainError> = Outcome.Success(Unit)
    }

    private class Env(
        online: Boolean = true,
        prices: PriceCatalogSnapshot = PriceCatalogSnapshot.EMPTY,
        unlocked: Boolean = true,
        clock: TestClock,
    ) {
        val connectivity = TestConnectivity(online)
        val catalog = Prices(prices)
        val lock = Lock(unlocked)
        val stranded = MutableStateFlow(emptySet<String>())
        val conditions = SnoozeConditions(connectivity, catalog, lock, clock, stranded)
    }

    private fun TestScope.collecting(env: Env) = env.conditions.observe().launchIn(backgroundScope)

    @Test
    fun `before anything was collected the env is online with no prices, so never Available and never a false offline`() {
        val env = Env(online = false, prices = allPrices, clock = TestClock(now))

        assertEquals(SnoozeEnv(true, PriceCatalogSnapshot.EMPTY, now, true), env.conditions.current())
        val policy = LiveSnoozeAvailability(env.conditions, UsdFeeLadder, logger)
        assertEquals(unavailable(UnavailableReason.CatalogueNotLoaded), policy.availability(session()))
    }

    @Test
    fun `the env changes in place as the connection, the prices, the unlock and a stranded payment change`() =
        runTest(UnconfinedTestDispatcher()) {
            val clock = TestClock(now)
            val env = Env(online = false, unlocked = false, clock = clock)
            val policy = LiveSnoozeAvailability(env.conditions, UsdFeeLadder, logger)
            val declined = session(declined = p01)
            val emitted = mutableListOf<SnoozeEnv>()
            backgroundScope.launch { env.conditions.observe().collect { emitted += it } }

            assertEquals(unavailable(UnavailableReason.BeforeFirstUnlock), policy.availability(declined))
            env.lock.state.value = true
            assertEquals(unavailable(UnavailableReason.Offline), policy.availability(declined))
            env.connectivity.online = true
            assertEquals(unavailable(UnavailableReason.CatalogueNotLoaded), policy.availability(declined))
            env.catalog.state.value = allPrices
            assertEquals(available(1, 1), policy.availability(declined))
            env.stranded.value = setOf(p01)
            assertEquals(unavailable(UnavailableReason.EarlierPaymentRefunding, price(1)), policy.availability(declined))
            env.stranded.value = emptySet()
            assertEquals(available(1, 1), policy.availability(declined))
            env.connectivity.online = false
            assertEquals(unavailable(UnavailableReason.Offline), policy.availability(declined))

            clock.advanceBy(1.days)
            assertEquals(now + 1.days, env.conditions.current().now, "the time is read at each call")
            // One env per change, in order: (online, unlocked, prices loaded, stranded).
            assertEquals(
                listOf(
                    listOf(false, false, false, false),
                    listOf(false, true, false, false),
                    listOf(true, true, false, false),
                    listOf(true, true, true, false),
                    listOf(true, true, true, true),
                    listOf(true, true, true, false),
                    listOf(false, true, true, false),
                ),
                emitted.map { listOf(it.online, it.userUnlocked, it.prices.entries.isNotEmpty(), it.strandedProducts.isNotEmpty()) },
            )
        }

    @Test
    fun `the reducer accepts a snooze tap and a pay confirm exactly when the live policy offers one, for every reason`() =
        runTest(UnconfinedTestDispatcher()) {
            val live = Env(clock = TestClock(now))
            collecting(live)
            val policy = LiveSnoozeAvailability(live.conditions, UsdFeeLadder, logger)
            val reducer = SessionReducer(policy, PluginCheckValidator, CameraFallbackPolicy())

            table.forEach { (name, input, expected) ->
                val (session, env) = input
                live.connectivity.online = env.online
                live.catalog.state.value = env.prices
                live.lock.state.value = env.userUnlocked
                live.stranded.value = env.strandedProducts
                assertEquals(expected, policy.availability(session), name)

                val ringing = SessionState.Ringing(session)
                val tapped = reducer.reduce(ringing, SessionEvent.SnoozeTapped, T0)
                val sheet = tapped.effects.filterIsInstance<SessionEffect.ShowSnoozeConfirm>()
                val paid = reducer.reduce(ringing, SessionEvent.PayConfirmed(PurchaseIntentId("intent-1")), T0)
                val paying = (paid.state as? SessionState.Active)?.session?.paying
                if (expected is SnoozeAvailability.Available) {
                    assertEquals(listOf(SessionEffect.ShowSnoozeConfirm(expected.offer)), sheet, name)
                    assertEquals(PurchaseIntentId("intent-1"), paying, name)
                } else {
                    assertEquals(emptyList(), sheet, name)
                    assertEquals(null, paying, "$name: no purchase starts")
                }
            }
        }

    @Test
    fun `once the last collector stops, the env falls back to safe, so the next ring never starts from a stale connection`() =
        runTest(UnconfinedTestDispatcher()) {
            val env = Env(online = true, prices = allPrices, clock = TestClock(now))
            val policy = LiveSnoozeAvailability(env.conditions, UsdFeeLadder, logger)
            val first = env.conditions.observe().launchIn(backgroundScope)
            val second = env.conditions.observe().launchIn(backgroundScope)
            assertEquals(available(1, 1), policy.availability(session()))

            first.cancel()
            assertEquals(available(1, 1), policy.availability(session()), "another collector still watches")
            second.cancel()
            env.connectivity.online = false

            assertEquals(SnoozeEnv(true, PriceCatalogSnapshot.EMPTY, now, true), env.conditions.current())
            assertEquals(unavailable(UnavailableReason.CatalogueNotLoaded), policy.availability(session()), "never a stale Available")

            env.conditions.observe().launchIn(backgroundScope)
            assertEquals(unavailable(UnavailableReason.Offline), policy.availability(session()), "the next collector sees the truth")
        }

    @Test
    fun `the reducer accepts a snooze tap exactly when the live policy offers one, with the shown price`() =
        runTest(UnconfinedTestDispatcher()) {
            val env = Env(online = false, prices = allPrices, clock = TestClock(now))
            collecting(env)
            val policy = LiveSnoozeAvailability(env.conditions, UsdFeeLadder, logger)
            val reducer = SessionReducer(policy, PluginCheckValidator, CameraFallbackPolicy())
            val ringing = SessionState.Ringing(session())

            val offline = reducer.reduce(ringing, SessionEvent.SnoozeTapped, T0)
            assertEquals(emptyList(), offline.effects.filterIsInstance<SessionEffect.ShowSnoozeConfirm>(), "offline: no sheet")

            env.connectivity.online = true
            val online = reducer.reduce(ringing, SessionEvent.SnoozeTapped, T0)
            assertEquals(
                listOf(SessionEffect.ShowSnoozeConfirm(SnoozeOffer(p01, 1, price(1)))),
                online.effects.filterIsInstance<SessionEffect.ShowSnoozeConfirm>(),
            )
        }
}

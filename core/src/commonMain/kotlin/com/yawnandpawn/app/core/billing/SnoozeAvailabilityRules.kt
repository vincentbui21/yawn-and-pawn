package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.net.Connectivity
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.SnoozeAvailabilityPolicy
import com.yawnandpawn.app.core.session.SnoozeOffer
import com.yawnandpawn.app.core.session.UnavailableReason
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlin.time.Instant

/**
 * What snooze availability needs beyond the session (Story 4.7, AD-7): whether the phone is [online], the cached Play
 * [prices] and the time [now] to judge their age, whether the user has unlocked since boot ([userUnlocked]), and the
 * products with a stranded payment ([strandedProducts], from the reconciler in Story 4.11).
 */
data class SnoozeEnv(
    val online: Boolean,
    val prices: PriceCatalogSnapshot,
    val now: Instant,
    val userUnlocked: Boolean,
    val strandedProducts: Set<String> = emptySet(),
)

/**
 * Whether the user can buy the next snooze of [session] in [env] (AD-7, FR-RNG-7): the first reason that applies, in
 * this order, else Available with the cached Play price.
 * 1. [UnavailableReason.TestMode]: a test session never charges.
 * 2. [UnavailableReason.BeforeFirstUnlock]: Play Billing starts only after the first unlock (AD-15).
 * 3. [UnavailableReason.MaxSnoozesReached]: `snoozesGranted` ≥ the frozen `maxSnoozes`.
 * 4. [UnavailableReason.PriceCapReached] (or [UnavailableReason.InvalidFee] for a damaged frozen fee, logged): the
 *    ladder's answer for snooze `snoozesGranted + 1` ([nextAvailability]).
 * 5. [UnavailableReason.PaymentPending]: Play reported a pending payment this session.
 * 6. [UnavailableReason.EarlierPaymentRefunding]: the user declined reusing the stranded payment of exactly this
 *    product and it is still stranded; the result carries that product's cached price, if any. A stranded payment the
 *    user has not declined keeps snooze Available: the reuse offer comes on "Pay" (Story 4.11).
 * 7. [UnavailableReason.Offline].
 * 8. [UnavailableReason.CatalogueNotLoaded]: no displayable (cached, not expired) price for the product. Until the
 *    Play adapter (Story 4.12) nothing fills the cache, so this is also "billing unavailable".
 *
 * Pure apart from the [logger] line for an invalid fee. "I'm up" never depends on it.
 */
fun snoozeAvailability(
    session: SessionData,
    env: SnoozeEnv,
    ladder: FeeLadder,
    logger: Logger,
): SnoozeAvailability {
    val config = session.config
    return when {
        config.testMode -> {
            SnoozeAvailability.Unavailable(UnavailableReason.TestMode)
        }

        !env.userUnlocked -> {
            SnoozeAvailability.Unavailable(UnavailableReason.BeforeFirstUnlock)
        }

        session.snoozesGranted >= config.maxSnoozes -> {
            SnoozeAvailability.Unavailable(UnavailableReason.MaxSnoozesReached)
        }

        else -> {
            when (val step = ladder.nextAvailability(session, logger)) {
                is SnoozeAvailability.Unavailable -> step
                is SnoozeAvailability.Available -> onSale(session, step.offer, env)
            }
        }
    }
}

/** Rows 5 to 8 of [snoozeAvailability], for the ladder's [offer]. */
private fun onSale(
    session: SessionData,
    offer: SnoozeOffer,
    env: SnoozeEnv,
): SnoozeAvailability {
    val price = env.prices.displayablePriceFor(offer.productId, env.now)
    val refunding = session.declinedReuseProduct == offer.productId && offer.productId in env.strandedProducts
    return when {
        session.paymentPending -> SnoozeAvailability.Unavailable(UnavailableReason.PaymentPending)
        refunding -> SnoozeAvailability.Unavailable(UnavailableReason.EarlierPaymentRefunding, price)
        !env.online -> SnoozeAvailability.Unavailable(UnavailableReason.Offline)
        price == null -> SnoozeAvailability.Unavailable(UnavailableReason.CatalogueNotLoaded)
        else -> SnoozeAvailability.Available(offer.copy(price = price))
    }
}

/**
 * The live [SnoozeEnv] (Story 4.7): [connectivity], the cached prices of [catalog], the [userLock] state and the
 * [stranded] products (none until Story 4.11), with "now" from [clock].
 *
 * [observe] combines them and emits a new env on every change, so the wake screen re-renders the snooze control in
 * place. [current] is the latest env [observe] produced (the time and lock state read again), so the reducer accepts a
 * tap on exactly the button the screen shows. Nothing is collected eagerly: until a collector has seen every input once,
 * [current] is online with no prices, which is at best "prices not loaded yet", never Available and never a false
 * "offline".
 */
class SnoozeConditions(
    private val connectivity: Connectivity,
    private val catalog: PriceCatalog,
    private val userLock: UserLockState,
    private val clock: Clock,
    private val stranded: Flow<Set<String>> = flowOf(emptySet()),
) {
    private val latest = MutableStateFlow(Inputs(online = true, prices = PriceCatalogSnapshot.EMPTY, stranded = emptySet()))

    /** The latest env: the last inputs [observe] saw, with the time and the lock state read now. */
    fun current(): SnoozeEnv = latest.value.let { SnoozeEnv(it.online, it.prices, clock.now(), userLock.isUserUnlocked(), it.stranded) }

    /** The env now and after every change of an input (connectivity, prices, unlock, stranded payments). */
    fun observe(): Flow<SnoozeEnv> =
        combine(connectivity.observeOnline(), catalog.observe(), stranded, userLock.observe()) { online, prices, stranded, _ ->
            Inputs(online, prices, stranded)
        }.onEach { latest.value = it }
            .map { current() }

    private data class Inputs(
        val online: Boolean,
        val prices: PriceCatalogSnapshot,
        val stranded: Set<String>,
    )
}

/**
 * The production [SnoozeAvailabilityPolicy] (Story 4.7, replacing the Epic 1 `NoBillingSnoozeAvailability`):
 * [snoozeAvailability] over the latest env of [conditions], with the session's frozen fee on [ladder].
 */
class LiveSnoozeAvailability(
    private val conditions: SnoozeConditions,
    private val ladder: FeeLadder,
    private val logger: Logger,
) : SnoozeAvailabilityPolicy {
    override fun availability(session: SessionData): SnoozeAvailability = snoozeAvailability(session, conditions.current(), ladder, logger)
}

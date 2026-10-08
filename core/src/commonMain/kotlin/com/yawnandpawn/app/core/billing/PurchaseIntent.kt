package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Play's LIVE price of [productId], read from its `ProductDetails` when the user tapped Pay (Story 4.8; 4.3 review
 * handoff): [price] as micros and currency (AD-8) and [formattedPrice] as Play's own text ("€1.19"). Never the cached
 * display price (Story 4.3), which can be stale: this is what the purchase record will show as charged. Billing cannot
 * launch without the product's `ProductDetails`, so a Pay always has one.
 */
data class LivePrice(
    val productId: String,
    val price: Money,
    val formattedPrice: String,
)

/**
 * A payment attempt, persisted before billing launches (AD-7, Story 4.8): the session it pays for, the product and
 * snooze number the confirm sheet offered (`snoozesGranted + 1`), the LIVE Play price at the Pay tap ([price] and
 * [formattedPrice], from the event's [LivePrice], never the cached display price) and when it was made ([createdAt]).
 * The reducer builds it from `PayConfirmed`; the engine commits it in the same `runtime.db` transaction as the state.
 */
data class PurchaseIntent(
    val intentId: PurchaseIntentId,
    val sessionId: String,
    val productId: String,
    val snoozeNumber: Int,
    val price: Money,
    val formattedPrice: String,
    val createdAt: Instant,
) {
    companion object {
        /**
         * How long intents are kept (owner-approved default 2026-09-26): long enough to price a pending payment that
         * completes after its session ended.
         */
        val RETENTION: Duration = 7.days
    }
}

/**
 * Read side of the persisted intents (`purchase_intent` in `runtime.db`). There is no save: intents are written only
 * by `SessionEngine`, through `ActiveSessionStore.commit(state, writes)`, in the transaction of the transition that
 * sets `paying`.
 */
interface PurchaseIntentStore {
    /** The intent [intentId], or `NotFound`. */
    suspend fun get(intentId: PurchaseIntentId): Outcome<PurchaseIntent, DomainError>

    /** Every intent of [sessionId], oldest first. */
    suspend fun forSession(sessionId: String): Outcome<List<PurchaseIntent>, DomainError>

    /** The intents of [sessionId] for [productId], oldest first. */
    suspend fun forProduct(
        sessionId: String,
        productId: String,
    ): Outcome<List<PurchaseIntent>, DomainError>

    /** Deletes every intent created before [instant]; returns how many were deleted. */
    suspend fun purgeOlderThan(instant: Instant): Outcome<Int, DomainError>
}

/** App start (Story 4.8): deletes the intents older than [PurchaseIntent.RETENTION]. A failure is logged only. */
class PurgeOldPurchaseIntents(
    private val store: PurchaseIntentStore,
    private val clock: Clock,
    private val logger: Logger,
) {
    suspend operator fun invoke(): Outcome<Int, DomainError> {
        val purged = store.purgeOlderThan(clock.now() - PurchaseIntent.RETENTION)
        if (purged is Outcome.Failure) logger.log(LogEvent.OperationFailed.of("purge purchase intents", purged.error))
        return purged
    }
}

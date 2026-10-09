package com.yawnandpawn.app.android.billing

import android.app.Activity
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.yawnandpawn.app.core.billing.LaunchResult
import com.yawnandpawn.app.core.billing.LivePrice
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.ProductDetailsResult
import com.yawnandpawn.app.core.billing.ProductPrice
import com.yawnandpawn.app.core.billing.PurchaseIntent
import com.yawnandpawn.app.core.billing.PurchaseSnapshot
import com.yawnandpawn.app.core.billing.PurchaseUpdate
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.valueOrNull
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.ConsumeResult
import com.yawnandpawn.app.core.session.PurchaseFailureKind
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.time.MonotonicClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The resumed wake screen, waited for up to a time limit (Story 4.12; [ResumedWakeScreen] in the app). */
fun interface WakeScreens {
    suspend fun await(timeout: Duration): Activity?
}

/**
 * How long each Play call may take (Story 4.12, defaults taken in fast mode). Play can hang (offline, S1 N1u: no
 * timeout on the sheet), so every wait is bounded; none of them is ever on the wake path anyway.
 */
data class BillingTimeouts(
    val connect: Duration = 10.seconds,
    val query: Duration = 10.seconds,
    val consumeCall: Duration = 15.seconds,
    /** Reaching the main thread to call `launchBillingFlow`; the call itself returns at once. */
    val launch: Duration = 5.seconds,
    /** The wake screen resuming, for example a moment after the PIN prompt closed. */
    val wakeScreen: Duration = 5.seconds,
    /** How long after a failed launch Play's copy of the code on the listener counts as an echo. */
    val echoWindow: Duration = 5.seconds,
    val reconnectFirst: Duration = 1.seconds,
    val reconnectMax: Duration = 60.seconds,
    val reconnectAttempts: Int = 6,
)

/**
 * The production [Billing] over Play Billing Library 9.1 (Story 4.12, AD-7), behind [BillingClientFacade].
 *
 * **Connection:** nothing connects before the first unlock (AD-15): every call answers "billing unavailable (before
 * first unlock)" until then. [init] (the first unlock) starts the connection on [scope] and returns at once; a call made
 * while disconnected connects first. One `startConnection` at a time (a second caller waits for the same setup). A lost
 * service reconnects with a backoff ([BillingTimeouts.reconnectFirst] doubling to [BillingTimeouts.reconnectMax], at
 * most [BillingTimeouts.reconnectAttempts] tries; a permanent answer such as no Play Store stops it), on top of Play's
 * own automatic reconnection.
 *
 * **Threads:** every call runs on the caller's coroutine (the coordinator's app scope, never the engine's step) and
 * suspends on Play's callbacks; only `launchBillingFlow` runs on [main], as Play requires. Every wait is bounded by
 * [timeouts], so a Play that never connects or never answers can hold nothing up: not "I'm up", the checks or the sound
 * (none of them waits for billing at all), and not the coordinator's next recovery.
 *
 * **Results:** the response codes map through [PlayResponses]. Updates go out on [purchaseUpdates], buffered until the
 * coordinator collects them, so none is lost; each sheet result is delivered once ([LaunchEchoFilter]). Product details
 * Play returned are kept in memory, so a launch needs no extra query after the live price was read. Purchase tokens
 * are never logged.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class AndroidBilling(
    private val facade: BillingClientFacade,
    private val userLock: UserLockState,
    private val wakeScreens: WakeScreens,
    private val clock: MonotonicClock,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val main: CoroutineContext,
    private val timeouts: BillingTimeouts = BillingTimeouts(),
) : Billing {
    private val updates = Channel<PurchaseUpdate>(Channel.UNLIMITED)
    private val echo = LaunchEchoFilter(timeouts.echoWindow.inWholeMilliseconds, clock::elapsedMillis)
    private val setupLock = Mutex()
    private var pendingSetup: CompletableDeferred<PlayResult>? = null
    private val initialised = AtomicBoolean(false)
    private var reconnecting: Job? = null
    private val products = ConcurrentHashMap<String, PlayProduct>()
    private val country = MutableStateFlow<String?>(null)

    /** The Play billing country (ISO 3166-1 alpha-2, upper case), read after each connection; null until known. */
    val billingCountry: StateFlow<String?> = country.asStateFlow()

    override val purchaseUpdates: Flow<PurchaseUpdate> = updates.receiveAsFlow()

    init {
        facade.setPurchasesListener(::onPurchasesUpdated)
    }

    /** The first unlock (AD-2 `InitBilling`, `UnlockSignals`): starts connecting on [scope]. Never waits, never throws. */
    override fun init() {
        if (initialised.compareAndSet(false, true)) reconnect()
    }

    override suspend fun launch(
        intent: PurchaseIntent,
        installId: String,
    ): Outcome<LaunchResult, DomainError> {
        when (val ready = ready()) {
            is Readiness.Ready -> Unit
            is Readiness.BeforeUnlock -> return Outcome.Failure(DomainError.BillingUnavailable(BEFORE_UNLOCK))
            is Readiness.Failed -> return Outcome.Success(LaunchResult.Failed(ready.kind))
        }
        val product =
            when (val found = productFor(intent.productId)) {
                is Lookup.Found -> found.product
                is Lookup.Missing -> return Outcome.Success(LaunchResult.Failed(found.kind))
            }
        val screen = wakeScreens.await(timeouts.wakeScreen)
        if (screen == null) {
            logger.log(LogEvent.OperationFailed(LAUNCH, "wake screen not resumed"))
            return Outcome.Success(LaunchResult.Failed(PurchaseFailureKind.Error))
        }
        val answer = launchOnMain(screen, product, installId, intent.sessionId)
        if (answer == null) {
            logger.log(LogEvent.OperationFailed(LAUNCH, "main thread busy"))
            return Outcome.Success(LaunchResult.Failed(PurchaseFailureKind.Error))
        }
        if (answer.code != BillingResponseCode.OK) logger.log(LogEvent.OperationFailed(LAUNCH, PlayResponses.name(answer.code)))
        return Outcome.Success(PlayResponses.launchResult(answer.code))
    }

    /**
     * Calls `launchBillingFlow` on [main] inside the echo window. Null when the main thread was not reached in time (then
     * nothing was launched). Once the call ran its answer is kept, even if the time limit ends as it returns.
     */
    private suspend fun launchOnMain(
        screen: Activity,
        product: PlayProduct,
        accountId: String,
        profileId: String,
    ): PlayResult? {
        var answer: PlayResult? = null
        withTimeoutOrNull(timeouts.launch) {
            withContext(main) {
                echo.begin()
                val result =
                    try {
                        facade.launchBillingFlow(screen, product, accountId, profileId)
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: RuntimeException,
                    ) {
                        PlayResult(BillingResponseCode.ERROR, e::class.simpleName.orEmpty())
                    }
                answer = result
                echo.end(result.code).forEach(::deliver)
            }
        }
        return answer
    }

    override suspend fun queryPurchases(): Outcome<List<PurchaseSnapshot>, DomainError> {
        when (val ready = ready()) {
            is Readiness.Ready -> Unit
            is Readiness.BeforeUnlock -> return Outcome.Failure(DomainError.BillingUnavailable(BEFORE_UNLOCK))
            is Readiness.Failed -> return Outcome.Failure(DomainError.BillingUnavailable(ready.cause))
        }
        val answer =
            call<PurchasesQuery>(timeouts.query) { done -> facade.queryPurchases(done) }
                ?: return Outcome.Failure(DomainError.BillingUnavailable("query purchases: no answer within ${timeouts.query}"))
        return if (answer.result.code == BillingResponseCode.OK) {
            Outcome.Success(snapshotsOf(answer.purchases))
        } else {
            Outcome.Failure(DomainError.BillingUnavailable("query purchases: ${PlayResponses.name(answer.result.code)}"))
        }
    }

    /** Only `PurchaseLedger` calls it (Story 4.10). `ITEM_NOT_OWNED` is [ConsumeResult.NotOwned], never consumed. */
    override suspend fun consume(token: PurchaseToken): ConsumeResult {
        when (val ready = ready()) {
            is Readiness.Ready -> Unit
            is Readiness.BeforeUnlock -> return ConsumeResult.Failed(BEFORE_UNLOCK)
            is Readiness.Failed -> return ConsumeResult.Failed(ready.cause)
        }
        val answer =
            call<PlayResult>(timeouts.consumeCall) { done -> facade.consumeAsync(token.value, done) }
                ?: return ConsumeResult.Failed("no answer within ${timeouts.consumeCall}")
        return PlayResponses.consumeResult(answer.code)
    }

    /**
     * Play's prices of [productIds] (`queryProductDetailsAsync`, INAPP), for the price cache (Story 4.3's
     * `ProductDetailsSource`) and the live price at Pay. Each product's first one-time offer gives the price; a product
     * Play reported as unfetched, did not return, or returned without a usable price is reported as unfetched, and the
     * rest of the answer still counts. Queried 20 ids at a time.
     */
    suspend fun productDetails(productIds: List<String>): Outcome<ProductDetailsResult, DomainError> {
        when (val ready = ready()) {
            is Readiness.Ready -> Unit
            is Readiness.BeforeUnlock -> return Outcome.Failure(DomainError.ProductDetailsFailed(transient = true, cause = BEFORE_UNLOCK))
            is Readiness.Failed -> return Outcome.Failure(DomainError.ProductDetailsFailed(ready.transient, ready.cause))
        }
        val prices = mutableListOf<ProductPrice>()
        val unfetched = mutableSetOf<String>()
        for (chunk in productIds.distinct().chunked(PRODUCTS_PER_QUERY)) {
            val answer =
                call<ProductQuery>(timeouts.query) { done -> facade.queryProducts(chunk, done) }
                    ?: return Outcome.Failure(
                        DomainError.ProductDetailsFailed(transient = true, cause = "no answer within ${timeouts.query}"),
                    )
            val code = answer.result.code
            if (code != BillingResponseCode.OK) {
                return Outcome.Failure(
                    DomainError.ProductDetailsFailed(PlayResponses.isTransient(code), "query product details: ${PlayResponses.name(code)}"),
                )
            }
            val returned = answer.products.filter { it.productId in chunk && it.productId !in answer.unfetched }
            returned.forEach { products[it.productId] = it }
            val priced = returned.mapNotNull { it.toPrice() }
            prices += priced
            unfetched += chunk.filter { id -> priced.none { it.productId == id } }
        }
        return Outcome.Success(ProductDetailsResult(prices, unfetched))
    }

    /** Play's live price of [productId] now (Story 4.13's live price at Pay), or null when Play gives none. */
    suspend fun livePrice(productId: String): LivePrice? =
        productDetails(listOf(productId))
            .valueOrNull()
            ?.prices
            ?.firstOrNull { it.productId == productId }
            ?.let { LivePrice(it.productId, it.price, it.formattedPrice) }

    private suspend fun productFor(productId: String): Lookup {
        products[productId]?.let { return Lookup.Found(it) }
        return when (val fetched = productDetails(listOf(productId))) {
            is Outcome.Success -> {
                products[productId]?.let { Lookup.Found(it) } ?: Lookup.Missing(PurchaseFailureKind.Error)
            }

            is Outcome.Failure -> {
                logger.log(LogEvent.OperationFailed.of(PRODUCT, fetched.error))
                val transient = (fetched.error as? DomainError.ProductDetailsFailed)?.transient == true
                Lookup.Missing(if (transient) PurchaseFailureKind.Offline else PurchaseFailureKind.Error)
            }
        }
    }

    private fun PlayProduct.toPrice(): ProductPrice? {
        val text = formattedPrice?.takeIf { it.isNotBlank() } ?: return null
        val micros = priceMicros?.takeIf { it > 0 } ?: return null
        val money = Money.parse(micros, currency.orEmpty()).valueOrNull() ?: return null
        return ProductPrice(productId, text, money)
    }

    // ---- connection -------------------------------------------------------------------------------------------------

    private sealed interface Readiness {
        data object Ready : Readiness

        data object BeforeUnlock : Readiness

        /** [kind] for a launch, [transient] for a price refresh, [cause] for the log. */
        data class Failed(
            val kind: PurchaseFailureKind,
            val transient: Boolean,
            val cause: String,
        ) : Readiness
    }

    private sealed interface Lookup {
        data class Found(
            val product: PlayProduct,
        ) : Lookup

        data class Missing(
            val kind: PurchaseFailureKind,
        ) : Lookup
    }

    /** Connected, or why not: before the first unlock nothing ever connects (AD-15). */
    private suspend fun ready(): Readiness {
        if (!userLock.isUserUnlocked()) return Readiness.BeforeUnlock
        if (facade.isReady) return Readiness.Ready
        val setup =
            connect()
                ?: return Readiness.Failed(
                    PurchaseFailureKind.Error,
                    transient = true,
                    cause = "connect: no answer within ${timeouts.connect}",
                )
        return if (setup.code == BillingResponseCode.OK) {
            Readiness.Ready
        } else {
            val name = PlayResponses.name(setup.code)
            Readiness.Failed(PlayResponses.failureKind(setup.code), PlayResponses.isTransient(setup.code), "connect: $name")
        }
    }

    /**
     * Starts the connection, or joins the one being set up, and waits up to [BillingTimeouts.connect] for its answer;
     * null when none came in time (the setup stays joinable: Play refuses a second `startConnection` while connecting).
     */
    private suspend fun connect(): PlayResult? {
        if (facade.isReady) return PlayResult(BillingResponseCode.OK)
        val setup =
            setupLock.withLock {
                pendingSetup?.takeIf { it.isActive } ?: CompletableDeferred<PlayResult>().also { started ->
                    pendingSetup = started
                    try {
                        facade.startConnection(onSetup = { result -> onSetup(started, result) }, onDisconnected = ::onDisconnected)
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: RuntimeException,
                    ) {
                        started.complete(PlayResult(BillingResponseCode.ERROR, e::class.simpleName.orEmpty()))
                    }
                }
            }
        return withTimeoutOrNull(timeouts.connect) { setup.await() }
    }

    private fun onSetup(
        setup: CompletableDeferred<PlayResult>,
        result: PlayResult,
    ) {
        setup.complete(result)
        if (result.code == BillingResponseCode.OK) scope.launch { readCountry() }
    }

    /** Play dropped the service: reconnect with the backoff (Play's automatic reconnection may beat it). */
    private fun onDisconnected() {
        if (initialised.get()) reconnect()
    }

    @Synchronized
    private fun reconnect() {
        if (reconnecting?.isActive == true) return
        reconnecting = scope.launch { reconnectWithBackoff() }
    }

    private suspend fun reconnectWithBackoff() {
        var wait = timeouts.reconnectFirst
        repeat(timeouts.reconnectAttempts) { attempt ->
            if (!userLock.isUserUnlocked()) return
            val setup = connect()
            if (setup?.code == BillingResponseCode.OK) return
            logger.log(
                LogEvent.OperationFailed(
                    CONNECT,
                    setup?.let { PlayResponses.name(it.code) } ?: "no answer within ${timeouts.connect}",
                ),
            )
            // No Play Store, Play too old or a misconfigured app: trying again will not help until something changes.
            if (setup != null && !PlayResponses.isTransient(setup.code)) return
            if (attempt < timeouts.reconnectAttempts - 1) {
                delay(wait)
                wait = (wait * 2).coerceAtMost(timeouts.reconnectMax)
            }
        }
    }

    private suspend fun readCountry() {
        val answer = call<Pair<PlayResult, String?>>(timeouts.query) { done -> facade.queryBillingCountry { r, c -> done(r to c) } }
        val code =
            answer
                ?.second
                ?.trim()
                ?.uppercase()
                ?.takeIf { answer.first.code == BillingResponseCode.OK && it.length == 2 }
        if (code != null) country.value = code
    }

    // ---- updates ----------------------------------------------------------------------------------------------------

    private fun onPurchasesUpdated(
        result: PlayResult,
        purchases: List<PlayPurchase>?,
    ) {
        val update = LaunchEchoFilter.Update(result, purchases)
        if (!echo.intercept(update)) deliver(update)
    }

    private fun deliver(update: LaunchEchoFilter.Update) {
        val mapped = PlayResponses.update(update.result.code, snapshotsOf(update.purchases.orEmpty()))
        if (update.result.code != BillingResponseCode.OK) {
            logger.log(LogEvent.OperationFailed(UPDATE, PlayResponses.name(update.result.code)))
        }
        updates.trySend(mapped)
    }

    /**
     * Runs one Play call that answers through a callback and waits up to [timeout] for its answer: null when none came
     * (or the call threw). A late or second callback is ignored.
     */
    private suspend fun <T : Any> call(
        timeout: Duration,
        start: (done: (T) -> Unit) -> Unit,
    ): T? =
        withTimeoutOrNull(timeout) {
            suspendCancellableCoroutine<T?> { waiting ->
                val answered = AtomicBoolean(false)
                try {
                    start { value -> if (answered.compareAndSet(false, true)) waiting.resume(value) }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: RuntimeException,
                ) {
                    logger.log(LogEvent.OperationFailed(CALL, e::class.simpleName.orEmpty()))
                    if (answered.compareAndSet(false, true)) waiting.resume(null)
                }
            }
        }

    companion object {
        /** The cause of every answer before the first unlock (AD-15): billing never connects then. */
        const val BEFORE_UNLOCK = "before first unlock"
        const val PRODUCTS_PER_QUERY = 20
        private const val LAUNCH = "launch billing"
        private const val CONNECT = "connect billing"
        private const val UPDATE = "purchase update"
        private const val PRODUCT = "product details for launch"
        private const val CALL = "billing call"
    }
}

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
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** A setup with no answer by then is ended (`endConnection`), so the next call builds a fresh client. */
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
    /** A connection that lasted this long resets the backoff when it drops; a shorter one keeps it growing. */
    val stableConnection: Duration = 60.seconds,
)

/**
 * The production [Billing] over Play Billing Library 9.1 (Story 4.12, AD-7), behind [BillingClientFacade].
 *
 * **Connection:** nothing connects before the first unlock (AD-15): every call answers "billing unavailable (before
 * first unlock)" until then. [init] (the first unlock) starts the connection on [scope] and returns at once; a call made
 * while disconnected connects first. One `startConnection` at a time: a caller that comes while a setup runs waits for
 * the same one. A setup with no answer within [BillingTimeouts.connect] is ended, so the next call starts afresh. A lost
 * service reconnects with a backoff ([BillingTimeouts.reconnectFirst] doubling to [BillingTimeouts.reconnectMax], at most
 * [BillingTimeouts.reconnectAttempts] tries per loop); the backoff keeps growing across drops until a connection stays up
 * for [BillingTimeouts.stableConnection], so a flapping Play Store is not rebound in a storm. No Play Store or a Play too
 * old for Billing stops it. Play's own automatic reconnection runs too.
 *
 * **Threads:** every call runs on the caller's coroutine (the coordinator's app scope, never the engine's step) and
 * suspends on Play's callbacks; only `launchBillingFlow` runs on [main], as Play requires. Every wait is bounded by
 * [timeouts], so a Play that never connects or never answers can hold nothing up: not "I'm up", the checks or the sound
 * (none of them waits for billing at all), and not the coordinator's next recovery.
 *
 * **Never behind the keyguard:** right before `launchBillingFlow`, on the main thread, [keyguardLocked] is read again
 * (the screen may have locked while the coordinator prepared): locked, nothing opens and the launch fails as
 * `UnlockFailed` ("Phone still locked. No charge."), since Play's sheet would wait behind the keyguard (S1 V1s).
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
    private val keyguardLocked: () -> Boolean = { false },
) : Billing {
    private val updates = Channel<PurchaseUpdate>(Channel.UNLIMITED)
    private val echo = LaunchEchoFilter(timeouts.echoWindow.inWholeMilliseconds, clock::elapsedMillis)
    private val setupLock = Mutex()
    private var pendingSetup: CompletableDeferred<PlayResult>? = null
    private val initialised = AtomicBoolean(false)
    private var reconnecting: Job? = null

    /** When the current connection came up (monotonic), or null while disconnected. */
    @Volatile
    private var connectedAt: Long? = null

    /** The wait before the next reconnect attempt, and the one after it (guarded by `this`). */
    private var nextDelay: Duration = Duration.ZERO
    private var backoff: Duration = timeouts.reconnectFirst

    private val products = ConcurrentHashMap<String, PlayProduct>()
    private val country = MutableStateFlow<String?>(null)

    /** The Play billing country (ISO 3166-1 alpha-2, upper case), read once connected; null until known. */
    val billingCountry: StateFlow<String?> = country.asStateFlow()

    override val purchaseUpdates: Flow<PurchaseUpdate> = updates.receiveAsFlow()

    init {
        facade.setPurchasesListener(::onPurchasesUpdated)
    }

    /**
     * The first unlock (AD-2 `InitBilling`, `UnlockSignals`): starts connecting on [scope]. Never waits, never throws.
     * Called while the user is still locked, it connects once the user unlocks (never before, AD-15).
     */
    override fun init() {
        if (initialised.compareAndSet(false, true)) {
            scope.launch {
                userLock.observe().first { it }
                reconnect()
            }
        }
    }

    override suspend fun launch(
        intent: PurchaseIntent,
        installId: String,
    ): Outcome<LaunchResult, DomainError> =
        whenReady({ notReady ->
            if (notReady.beforeUnlock) {
                Outcome.Failure(DomainError.BillingUnavailable(notReady.cause))
            } else {
                Outcome.Success(LaunchResult.Failed(notReady.kind))
            }
        }) { Outcome.Success(launchConnected(intent, installId)) }

    /** The launch once connected: the product, then the resumed wake screen, then `launchBillingFlow` on [main]. */
    private suspend fun launchConnected(
        intent: PurchaseIntent,
        installId: String,
    ): LaunchResult {
        val lookup = productFor(intent.productId)
        val screen = if (lookup is Lookup.Found) wakeScreens.await(timeouts.wakeScreen) else null
        val attempt =
            if (lookup is Lookup.Found && screen != null) {
                launchOnMain(screen, lookup.product, installId, intent.sessionId)
            } else {
                LaunchAttempt.NotReached
            }
        return when {
            lookup is Lookup.Missing -> {
                LaunchResult.Failed(lookup.kind)
            }

            screen == null -> {
                logger.log(LogEvent.OperationFailed(LAUNCH, "wake screen not resumed"))
                LaunchResult.Failed(PurchaseFailureKind.Error)
            }

            else -> {
                resultOf(attempt)
            }
        }
    }

    private fun resultOf(attempt: LaunchAttempt): LaunchResult =
        when (attempt) {
            LaunchAttempt.NotReached -> {
                logger.log(LogEvent.OperationFailed(LAUNCH, "main thread busy"))
                LaunchResult.Failed(PurchaseFailureKind.Error)
            }

            LaunchAttempt.Locked -> {
                logger.log(LogEvent.OperationFailed(LAUNCH, "keyguard locked"))
                LaunchResult.Failed(PurchaseFailureKind.UnlockFailed)
            }

            is LaunchAttempt.Answered -> {
                val code = attempt.result.code
                if (code != BillingResponseCode.OK) logger.log(LogEvent.OperationFailed(LAUNCH, PlayResponses.name(code)))
                PlayResponses.launchResult(code)
            }
        }

    /** How a call to `launchBillingFlow` went. */
    private sealed interface LaunchAttempt {
        /** The main thread was not reached in time: nothing was launched, and nothing will be. */
        data object NotReached : LaunchAttempt

        /** The keyguard was up: nothing was launched. */
        data object Locked : LaunchAttempt

        data class Answered(
            val result: PlayResult,
        ) : LaunchAttempt
    }

    /**
     * Calls `launchBillingFlow` on [main] inside the echo window, unless the keyguard is up. The call is posted to [main]
     * and waited for up to [BillingTimeouts.launch]: a main thread busy past that gives [LaunchAttempt.NotReached] at once,
     * and the posted call then never runs (it is claimed first). A call that already started is waited for, so its answer
     * is never lost. Whatever the call throws (an `Error` too), the echo window is closed, so updates held during the call
     * are never held for ever (review 8).
     */
    private suspend fun launchOnMain(
        screen: Activity,
        product: PlayProduct,
        accountId: String,
        profileId: String,
    ): LaunchAttempt {
        val claimed = AtomicBoolean(false)
        val posted =
            scope.async(main) {
                when {
                    !claimed.compareAndSet(false, true) -> LaunchAttempt.NotReached
                    keyguardLocked() -> LaunchAttempt.Locked
                    else -> LaunchAttempt.Answered(callLaunch(screen, product, accountId, profileId))
                }
            }
        val answer = withTimeoutOrNull(timeouts.launch) { posted.await() }
        return when {
            answer != null -> answer
            claimed.compareAndSet(false, true) -> LaunchAttempt.NotReached.also { posted.cancel() }
            else -> posted.await()
        }
    }

    /** `launchBillingFlow` itself, on the main thread, between the echo filter's begin and end. */
    private fun callLaunch(
        screen: Activity,
        product: PlayProduct,
        accountId: String,
        profileId: String,
    ): PlayResult {
        echo.begin()
        var result = PlayResult(BillingResponseCode.ERROR, "launch threw")
        try {
            result = facade.launchBillingFlow(screen, product, accountId, profileId)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            logger.log(LogEvent.OperationFailed(LAUNCH, e::class.simpleName.orEmpty()))
        } finally {
            echo.end(result.code).forEach(::deliver)
        }
        return result
    }

    override suspend fun queryPurchases(): Outcome<List<PurchaseSnapshot>, DomainError> =
        whenReady({ Outcome.Failure(DomainError.BillingUnavailable(it.cause)) }) {
            val answer = call<PurchasesQuery>(timeouts.query) { done -> facade.queryPurchases(done) }
            when {
                answer == null -> {
                    Outcome.Failure(DomainError.BillingUnavailable("query purchases: no answer within ${timeouts.query}"))
                }

                answer.result.code == BillingResponseCode.OK -> {
                    Outcome.Success(snapshotsOf(answer.purchases))
                }

                else -> {
                    Outcome.Failure(DomainError.BillingUnavailable("query purchases: ${PlayResponses.name(answer.result.code)}"))
                }
            }
        }

    /** Only `PurchaseLedger` calls it (Story 4.10). `ITEM_NOT_OWNED` is [ConsumeResult.NotOwned], never consumed. */
    override suspend fun consume(token: PurchaseToken): ConsumeResult =
        whenReady({ ConsumeResult.Failed(it.cause) }) {
            val answer = call<PlayResult>(timeouts.consumeCall) { done -> facade.consumeAsync(token.value, done) }
            if (answer == null) {
                ConsumeResult.Failed("no answer within ${timeouts.consumeCall}")
            } else {
                PlayResponses.consumeResult(answer.code)
            }
        }

    /**
     * Play's prices of [productIds] (`queryProductDetailsAsync`, INAPP), for the price cache (Story 4.3's
     * `ProductDetailsSource`) and the live price at Pay. Each product's first one-time offer gives the price; a product
     * Play reported as unfetched, did not return, or returned without a usable price is reported as unfetched, and the
     * rest of the answer still counts. Queried 20 ids at a time: when a later query fails, the earlier ones' prices are
     * kept and the rest reported unfetched; only a failed first query fails the whole fetch.
     */
    suspend fun productDetails(productIds: List<String>): Outcome<ProductDetailsResult, DomainError> =
        whenReady({ Outcome.Failure(DomainError.ProductDetailsFailed(it.transient, it.cause)) }) { queryProducts(productIds) }

    private suspend fun queryProducts(productIds: List<String>): Outcome<ProductDetailsResult, DomainError> {
        val prices = mutableListOf<ProductPrice>()
        val unfetched = mutableSetOf<String>()
        val chunks = productIds.distinct().chunked(PRODUCTS_PER_QUERY)
        var failure: DomainError? = null
        var answered = 0
        for (chunk in chunks) {
            val answer = call<ProductQuery>(timeouts.query) { done -> facade.queryProducts(chunk, done) }
            failure = failureOf(answer?.result?.code)
            if (answer == null || failure != null) break
            answered++
            val returned = answer.products.filter { it.productId in chunk && it.productId !in answer.unfetched }
            returned.forEach { products[it.productId] = it }
            val priced = returned.mapNotNull { it.toPrice() }
            prices += priced
            unfetched += chunk.filter { id -> priced.none { it.productId == id } }
        }
        val error = failure
        return when {
            error == null -> {
                Outcome.Success(ProductDetailsResult(prices, unfetched))
            }

            answered == 0 -> {
                Outcome.Failure(error)
            }

            else -> {
                logger.log(LogEvent.OperationFailed.of(PARTIAL, error))
                Outcome.Success(ProductDetailsResult(prices, unfetched + chunks.drop(answered).flatten()))
            }
        }
    }

    /** The failure of a product query that answered [code]; null (no answer) is a transient failure, OK none. */
    private fun failureOf(code: Int?): DomainError? =
        when (code) {
            null -> DomainError.ProductDetailsFailed(transient = true, cause = "no answer within ${timeouts.query}")
            BillingResponseCode.OK -> null
            else -> DomainError.ProductDetailsFailed(PlayResponses.isTransient(code), "query product details: ${PlayResponses.name(code)}")
        }

    /** Play's live price of [productId] now (Story 4.13's live price at Pay), or null when Play gives none. */
    suspend fun livePrice(productId: String): LivePrice? =
        productDetails(listOf(productId))
            .valueOrNull()
            ?.prices
            ?.firstOrNull { it.productId == productId }
            ?.let { LivePrice(it.productId, it.price, it.formattedPrice) }

    private suspend fun productFor(productId: String): Lookup {
        val known = products[productId]
        return if (known != null) Lookup.Found(known) else fetchProduct(productId)
    }

    private suspend fun fetchProduct(productId: String): Lookup =
        when (val fetched = productDetails(listOf(productId))) {
            is Outcome.Success -> {
                products[productId]?.let { Lookup.Found(it) } ?: Lookup.Missing(PurchaseFailureKind.Error)
            }

            is Outcome.Failure -> {
                logger.log(LogEvent.OperationFailed.of(PRODUCT, fetched.error))
                val transient = (fetched.error as? DomainError.ProductDetailsFailed)?.transient == true
                Lookup.Missing(if (transient) PurchaseFailureKind.Offline else PurchaseFailureKind.Error)
            }
        }

    private fun PlayProduct.toPrice(): ProductPrice? {
        val text = formattedPrice?.takeIf { it.isNotBlank() }
        val micros = priceMicros?.takeIf { it > 0 }
        val money = micros?.let { Money.parse(it, currency.orEmpty()).valueOrNull() }
        return if (text != null && money != null) ProductPrice(productId, text, money) else null
    }

    // ---- connection -------------------------------------------------------------------------------------------------

    private sealed interface Readiness {
        data object Ready : Readiness

        /**
         * Not connected: [beforeUnlock] (nothing connects before the first unlock), else [kind] for a launch and
         * [transient] for a price refresh; [cause] for the log.
         */
        data class NotReady(
            val kind: PurchaseFailureKind,
            val transient: Boolean,
            val cause: String,
            val beforeUnlock: Boolean = false,
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

    /** Runs [onReady] once connected, else answers [notReady]. */
    private suspend inline fun <T> whenReady(
        notReady: (Readiness.NotReady) -> T,
        onReady: () -> T,
    ): T =
        when (val state = ready()) {
            Readiness.Ready -> onReady()
            is Readiness.NotReady -> notReady(state)
        }

    /** Connected, or why not: before the first unlock nothing ever connects (AD-15). */
    private suspend fun ready(): Readiness =
        when {
            !userLock.isUserUnlocked() -> Readiness.NotReady(PurchaseFailureKind.Error, true, BEFORE_UNLOCK, beforeUnlock = true)
            facade.isReady -> Readiness.Ready
            else -> readinessOf(connect())
        }

    private fun readinessOf(setup: PlayResult?): Readiness =
        when {
            setup == null -> {
                Readiness.NotReady(PurchaseFailureKind.Error, true, "connect: no answer within ${timeouts.connect}")
            }

            setup.code == BillingResponseCode.OK -> {
                Readiness.Ready
            }

            else -> {
                val name = PlayResponses.name(setup.code)
                Readiness.NotReady(PlayResponses.failureKind(setup.code), PlayResponses.isTransient(setup.code), "connect: $name")
            }
        }

    /**
     * Starts the connection, or joins the one being set up, and waits up to [BillingTimeouts.connect] for its answer.
     * Null when none came in time: that setup is then ended (`endConnection`), so a setup that never answers is never
     * joined for ever, and the next call builds a fresh client (review 2).
     */
    private suspend fun connect(): PlayResult? {
        val setup = setupLock.withLock { pendingSetup?.takeIf { it.isActive } ?: startSetup() }
        val answer = withTimeoutOrNull(timeouts.connect) { setup.await() }
        if (answer == null) abandon(setup)
        return answer
    }

    private fun startSetup(): CompletableDeferred<PlayResult> =
        CompletableDeferred<PlayResult>().also { started ->
            pendingSetup = started
            try {
                facade.startConnection(onSetup = { result -> onSetup(started, result) }, onDisconnected = { onDisconnected(started) })
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                started.complete(PlayResult(BillingResponseCode.ERROR, e::class.simpleName.orEmpty()))
            }
        }

    /** [setup] never answered: ended, and the callers still waiting for it answered as disconnected. */
    private suspend fun abandon(setup: CompletableDeferred<PlayResult>) {
        setupLock.withLock {
            if (pendingSetup === setup && setup.complete(PlayResult(BillingResponseCode.SERVICE_DISCONNECTED, "setup timed out"))) {
                pendingSetup = null
                try {
                    facade.endConnection()
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: RuntimeException,
                ) {
                    logger.log(LogEvent.OperationFailed(CONNECT, e::class.simpleName.orEmpty()))
                }
            }
        }
    }

    private fun onSetup(
        setup: CompletableDeferred<PlayResult>,
        result: PlayResult,
    ) {
        // A late answer of an abandoned setup changes nothing.
        if (setup.complete(result) && result.code == BillingResponseCode.OK) {
            connectedAt = clock.elapsedMillis()
            if (country.value == null) scope.launch { readCountry() }
        }
    }

    /**
     * Play dropped the service ([setup]'s client). A setup still waiting is answered as disconnected. A connection that
     * stayed up for [BillingTimeouts.stableConnection] resets the backoff; a shorter one makes the next try wait longer
     * (review 13). Then it reconnects, once [init] has run.
     */
    private fun onDisconnected(setup: CompletableDeferred<PlayResult>) {
        setup.complete(PlayResult(BillingResponseCode.SERVICE_DISCONNECTED, "disconnected"))
        val since = connectedAt
        connectedAt = null
        val stable = since != null && clock.elapsedMillis() - since >= timeouts.stableConnection.inWholeMilliseconds
        if (stable) resetBackoff() else growBackoff()
        if (initialised.get()) reconnect()
    }

    @Synchronized
    private fun reconnect() {
        if (reconnecting?.isActive != true) reconnecting = scope.launch { reconnectWithBackoff() }
    }

    @Synchronized
    private fun resetBackoff() {
        nextDelay = Duration.ZERO
        backoff = timeouts.reconnectFirst
    }

    @Synchronized
    private fun growBackoff() {
        nextDelay = backoff
        backoff = (backoff * 2).coerceAtMost(timeouts.reconnectMax)
    }

    @Synchronized
    private fun takeDelay(): Duration = nextDelay.also { nextDelay = Duration.ZERO }

    /**
     * Connects until connected, at most [BillingTimeouts.reconnectAttempts] times, waiting the backoff before each try
     * after a failure or a drop. A permanent answer (no Play Store, Play too old for Billing) stops it: trying again will
     * not help until something changes. `DEVELOPER_ERROR` is not permanent here: Play answers it to a `startConnection`
     * while it is reconnecting by itself (review 10).
     */
    private suspend fun reconnectWithBackoff() {
        var attempts = 0
        var permanent = false
        while (!permanent && attempts < timeouts.reconnectAttempts && wantsConnection()) {
            val wait = takeDelay()
            if (wait > Duration.ZERO) delay(wait)
            if (facade.isReady) break
            val setup = connect()
            attempts++
            if (setup?.code != BillingResponseCode.OK) {
                val cause = setup?.let { PlayResponses.name(it.code) } ?: "no answer within ${timeouts.connect}"
                logger.log(LogEvent.OperationFailed(CONNECT, cause))
                permanent = setup != null && setup.code in PERMANENT_SETUP_FAILURES
                growBackoff()
            }
        }
    }

    /** Unlocked and not connected: a reconnect loop keeps trying. */
    private fun wantsConnection(): Boolean = userLock.isUserUnlocked() && !facade.isReady

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

        /** Setup answers that retrying will not change: no Play Store, or a Play Store without Billing. */
        private val PERMANENT_SETUP_FAILURES = setOf(BillingResponseCode.BILLING_UNAVAILABLE, BillingResponseCode.FEATURE_NOT_SUPPORTED)
        private const val LAUNCH = "launch billing"
        private const val CONNECT = "connect billing"
        private const val UPDATE = "purchase update"
        private const val PRODUCT = "product details for launch"
        private const val PARTIAL = "product details, later queries"
        private const val CALL = "billing call"
    }
}

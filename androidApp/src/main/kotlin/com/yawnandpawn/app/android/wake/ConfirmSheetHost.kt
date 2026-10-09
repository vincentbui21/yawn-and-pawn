package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.core.billing.DisplayPrices
import com.yawnandpawn.app.core.billing.FeeLadder
import com.yawnandpawn.app.core.billing.LivePrice
import com.yawnandpawn.app.core.billing.LivePriceSource
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PurchaseIntentStore
import com.yawnandpawn.app.core.billing.followingProduct
import com.yawnandpawn.app.core.error.valueOrNull
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.EffectRunner
import com.yawnandpawn.app.core.session.EntryEffect
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.PurchaseOutcome
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.ui.wake.PriceLookup
import com.yawnandpawn.app.ui.wake.SheetRequest
import com.yawnandpawn.app.ui.wake.SnoozeSheet
import com.yawnandpawn.app.ui.wake.WakeMessage
import com.yawnandpawn.app.ui.wake.snoozeSheet
import com.yawnandpawn.app.ui.wake.stillWanted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A payment message for one ring of one session (Story 4.13: only "Phone still locked. No charge." so far). */
data class RingMessage(
    val sessionId: String,
    val ringIndex: Int,
    val message: WakeMessage,
)

/**
 * The snooze confirm sheet's state and actions (Story 4.13), one per process (a Koin single), so a recreated wake screen
 * shows the same sheet. It never stands between the user and "I'm up": closing it (I'll get up, Back, a swipe, a tap
 * outside) always returns to the ring or check underneath, with no charge and no intent written.
 *
 * - **Opens** on the engine's `ShowSnoozeConfirm` ([onEffect], through [SheetEffectRunner]) and asks Play for the live
 *   price of the snooze and the next one ([livePrices]); until it arrives the cached display price ([display]) shows.
 * - **Pay** ([onUpper]) asks for the live price again, for at most [LIVE_PRICE_TIMEOUT]. A price other than the one on
 *   screen is shown instead (the sheet's 500 ms guard starts again) and nothing is sent: the user confirms the new
 *   price. The same price sends `PayConfirmed` with a new intent id ([ids], a UUID v4 per tap) and that live price. No
 *   live price (offline, not loaded, no answer in time) closes the sheet: billing could not launch, so nothing is
 *   charged. One request sends at most one Pay (review fix 12): a second tap, or one before the engine shows the first,
 *   does nothing; closing the sheet or "I'm up" first makes the request dead, so no Pay can follow them.
 * - **Unlocking:** while the session's `unlocking` is set, the sheet asks to unlock at the price that was sent (after a
 *   new process, the price stored with the intent, [intents]); "Cancel" sends `UnlockFailed` ("Phone still locked. No
 *   charge."). A lost unlock callback (back on screen with the unlock still pending) is not the sheet's: Story 4.11's
 *   `PurchaseCoordinator.onWakeScreenResumed` resolves it (settle, then recheck the keyguard) from `WakeActivity.onResume`
 *   (review fix 14, integration decision 2026-10-09).
 * - **Already paid** (`ShowReuseSheet`): "Use it" and "Not now" (or Back, a swipe) go to the coordinator's
 *   `acceptReuse()` / `declineReuse()` ([reuse]); the stranded token never reaches the UI (Story 4.11).
 * - The sheet closes for good when its ring ends or snooze stops being Available for that offer ([stillWanted]): the
 *   snooze button then shows why.
 */
@Suppress("LongParameterList", "TooManyFunctions") // The sheet's ports; one handler per action.
class ConfirmSheetHost(
    private val live: LivePriceSource,
    private val display: DisplayPrices,
    private val reuse: ReuseChoices,
    private val ladder: FeeLadder,
    private val ids: IdGenerator,
    private val intents: PurchaseIntentStore,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {
    private val requested = MutableStateFlow<SheetRequest?>(null)
    private val prices = MutableStateFlow<Map<String, LivePrice>>(emptyMap())
    private val sent = MutableStateFlow<Map<PurchaseIntentId, Money>>(emptyMap())
    private val shownMessage = MutableStateFlow<RingMessage?>(null)

    /** Orders "is the request still open, then send" against closing it, so nothing is sent after a close. */
    private val lock = Any()

    /** The request whose Pay was sent (compared by identity): it sends no other. */
    @Volatile
    private var paidRequest: SheetRequest? = null

    /** The Pay or "Use it" in progress (asking Play for the price or the token); one at a time. */
    @Volatile
    private var action: Job? = null

    /** What opened the sheet; null when closed. */
    val request: StateFlow<SheetRequest?> = requested.asStateFlow()

    /** The live prices Play gave since the process started, by product. */
    val livePrices: StateFlow<Map<String, LivePrice>> = prices.asStateFlow()

    /** The live price each Pay sent (or its stored intent's), by intent: the unlock step shows it. */
    val sentPrices: StateFlow<Map<PurchaseIntentId, Money>> = sent.asStateFlow()

    /** The payment message on the wake screen, if any. */
    val message: StateFlow<RingMessage?> = shownMessage.asStateFlow()

    /** The price the sheet shows for a product: Play's live price in [livePrices] when known, else the cached display price. */
    fun priceOf(livePrices: Map<String, LivePrice>): PriceLookup = { id -> livePrices[id]?.price ?: display.priceOf(id) }

    /**
     * The sheet for [session] with [availability] now, from [request], [livePrices] and [sentPrices] (all observed by the
     * screen), with the tax note when [showTaxNote].
     */
    @Suppress("LongParameterList") // The observed values the screen passes in, so it redraws on each.
    fun sheet(
        session: SessionData,
        availability: SnoozeAvailability,
        request: SheetRequest?,
        livePrices: Map<String, LivePrice>,
        sentPrices: Map<PurchaseIntentId, Money>,
        showTaxNote: Boolean,
    ): SnoozeSheet? {
        val priceOf = priceOf(livePrices)
        val unlocking = session.paying?.let(sentPrices::get)
        return snoozeSheet(request, session, availability, priceOf, ladder, showTaxNote, unlocking)
    }

    /** The message to show on [session]'s screen: only one of its current ring. */
    fun messageFor(
        session: SessionData,
        shown: RingMessage?,
    ): WakeMessage? = shown?.takeIf { it.sessionId == session.sessionId && it.ringIndex == session.ringIndex }?.message

    /**
     * Follows [session] with [availability]: a sheet that no longer belongs on its screen ([stillWanted]) closes for good,
     * and a pending unlock whose price this process does not know (a new process) reads it from its stored intent.
     */
    fun follow(
        session: SessionData,
        availability: SnoozeAvailability,
    ) {
        val paying = session.paying
        if (paying != null && session.unlocking && paying !in sent.value) loadSentPrice(paying)
        val current = requested.value ?: return
        if (paying == null && !current.stillWanted(session, availability)) close(current)
    }

    /** The engine's UI effects (run after the transition is committed; never dispatches). */
    fun onEffect(
        effect: SessionEffect,
        state: SessionState,
    ) {
        val session = (state as? SessionState.Ring)?.session
        when (effect) {
            is SessionEffect.ShowSnoozeConfirm -> {
                // While a payment is in flight Play's sheet (or the PIN) is on top; its result comes first.
                if (session == null || session.paying != null) return
                requested.value = SheetRequest.Confirm(session.sessionId, session.ringIndex, effect.offer)
                refreshPrices(listOfNotNull(effect.offer.productId, ladder.followingProduct(session, effect.offer)))
            }

            is SessionEffect.ShowReuseSheet -> {
                session ?: return
                requested.value = SheetRequest.AlreadyPaid(session.sessionId, session.ringIndex, effect.productId)
                refreshPrices(listOf(effect.productId))
            }

            SessionEffect.HideReuseSheet -> {
                requested.update { it.takeUnless { request -> request is SheetRequest.AlreadyPaid } }
            }

            is SessionEffect.ShowPurchaseOutcome -> {
                requested.value = null
                // Story 4.14 maps every outcome; 4.13 shows the unlock one its "Cancel" leads to.
                if (effect.outcome == PurchaseOutcome.UnlockFailed && session != null) {
                    shownMessage.value = RingMessage(session.sessionId, session.ringIndex, WakeMessage.UnlockFailed)
                }
            }

            SessionEffect.ShowPaymentPending -> {
                requested.value = null
            }

            else -> {
                Unit
            }
        }
    }

    /**
     * "Pay {price} and snooze" (at [shownPrice]) or "Use it", on [session] with [availability] now. Ignored while one is
     * already in progress, and after this request's Pay was sent. [send] dispatches in tap order.
     */
    fun onUpper(
        session: SessionData,
        availability: SnoozeAvailability,
        shownPrice: Money?,
        send: (List<SessionEvent>) -> Unit,
    ) {
        val request = requested.value
        when {
            request == null || action?.isActive == true || request === paidRequest -> {
                Unit
            }

            !request.stillWanted(session, availability) -> {
                close(request)
            }

            else -> {
                action =
                    when (request) {
                        is SheetRequest.Confirm -> scope.launch { pay(request, shownPrice, send) }
                        is SheetRequest.AlreadyPaid -> scope.launch { useEarlierPayment(request) }
                    }
            }
        }
    }

    private suspend fun pay(
        request: SheetRequest.Confirm,
        shownPrice: Money?,
        send: (List<SessionEvent>) -> Unit,
    ) {
        val productId = request.offer.productId
        val price = livePrice(productId)
        when {
            // No live price: billing could not launch, so the sheet closes with no charge (the button keeps its reason).
            price == null -> {
                if (sendWhileOpen(request, send) { listOf(SessionEvent.UserInteracted) }) {
                    logger.log(LogEvent.OperationFailed(OPERATION_LIVE_PRICE, "no live price"))
                    close(request)
                }
            }

            // A changed price is shown first (the sheet's guard starts again); the user confirms the price on screen.
            price.price != shownPrice -> {
                prices.update { it + (productId to price) }
            }

            // The request stays: the engine's `paying` hides the sheet (or shows the unlock step), and the payment's
            // outcome closes it. A Pay the engine ignores leaves the sheet as it was, with Pay spent.
            else -> {
                prices.update { it + (productId to price) }
                sendWhileOpen(request, send) {
                    val intentId = PurchaseIntentId(ids.newId())
                    sent.update { it + (intentId to price.price) }
                    paidRequest = request
                    listOf(SessionEvent.PayConfirmed(intentId, price))
                }
            }
        }
    }

    /** "Use it": only while [request] is still the open sheet (a close that came first wins); the coordinator reuses. */
    private suspend fun useEarlierPayment(request: SheetRequest.AlreadyPaid) {
        val open =
            synchronized(lock) {
                (requested.value === request).also { if (it) requested.value = null }
            }
        if (open) reuse.acceptReuse()
    }

    /**
     * Sends what [events] makes, only while [request] is still the open sheet; true when sent. Under [lock], so a close
     * ([onDismiss], [onImUp]) that came first always wins.
     */
    private fun sendWhileOpen(
        request: SheetRequest,
        send: (List<SessionEvent>) -> Unit,
        events: () -> List<SessionEvent>,
    ): Boolean =
        synchronized(lock) {
            if (requested.value !== request) return@synchronized false
            send(events())
            true
        }

    /**
     * "I'll get up", "Not now", "Cancel", Back, a swipe or a tap outside, on [session]: the sheet closes with no charge.
     * "Cancel" while the unlock is pending sends `UnlockFailed`; "Not now" declines the earlier payment through the
     * coordinator ([ReuseChoices.declineReuse], `ReuseDeclined`); otherwise it is a plain interaction.
     */
    fun onDismiss(
        session: SessionData,
        send: (List<SessionEvent>) -> Unit,
    ) {
        val request =
            synchronized(lock) {
                action?.cancel()
                requested.value.also { requested.value = null }
            }
        when {
            session.paying != null && session.unlocking -> send(listOf(SessionEvent.UserInteracted, SessionEvent.UnlockFailed))
            request is SheetRequest.AlreadyPaid -> scope.launch { reuse.declineReuse() }
            else -> send(listOf(SessionEvent.UserInteracted))
        }
    }

    /** "I'm up" (or any way past the sheet): a Pay still asking for its price is dropped and the sheet closes. */
    fun onImUp() {
        synchronized(lock) {
            action?.cancel()
            requested.value = null
        }
    }

    /** Any tap on the wake screen ends the payment message (Story 4.14 adds the 10 s minimum). */
    fun onTap() {
        shownMessage.value = null
    }

    /** The message's own end, [message] only (a newer one stays). */
    fun expire(message: RingMessage) {
        shownMessage.compareAndSet(message, null)
    }

    private fun close(request: SheetRequest) {
        requested.compareAndSet(request, null)
    }

    private suspend fun livePrice(productId: String): LivePrice? =
        withTimeoutOrNull(LIVE_PRICE_TIMEOUT) { live.livePrice(productId) }?.takeIf { it.productId == productId }

    private fun refreshPrices(productIds: List<String>) {
        productIds.forEach { productId ->
            scope.launch {
                val price = livePrice(productId) ?: return@launch
                prices.update { it + (productId to price) }
            }
        }
    }

    private fun loadSentPrice(intentId: PurchaseIntentId) {
        scope.launch {
            val stored = intents.get(intentId).valueOrNull() ?: return@launch
            sent.update { if (intentId in it) it else it + (intentId to stored.price) }
        }
    }

    companion object {
        /** How long Pay waits for Play's live price before it closes the sheet with no charge (review fix 13). */
        val LIVE_PRICE_TIMEOUT: Duration = 3.seconds

        private const val OPERATION_LIVE_PRICE = "snooze live price"
    }
}

/**
 * The engine's [EffectRunner] (Story 4.13): [runtime] carries out every effect as before, and the confirm sheet's
 * effects (`ShowSnoozeConfirm`, `ShowReuseSheet`, `HideReuseSheet`, `ShowPurchaseOutcome`, `ShowPaymentPending`) also
 * reach [sheet], with the engine's current [state]. A failure in the sheet is logged and never stops the runtime.
 */
class SheetEffectRunner(
    private val runtime: EffectRunner,
    private val sheet: ConfirmSheetHost,
    private val logger: Logger,
    private val state: () -> SessionState,
) : EffectRunner {
    @Suppress("TooGenericExceptionCaught") // Any sheet failure: the alarm's effects must still run.
    override suspend fun run(effect: SessionEffect) {
        try {
            sheet.onEffect(effect, state())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(LogEvent.OperationFailed("confirm sheet effect", e::class.simpleName.orEmpty()))
        }
        runtime.run(effect)
    }

    override suspend fun apply(effect: EntryEffect) = runtime.apply(effect)
}

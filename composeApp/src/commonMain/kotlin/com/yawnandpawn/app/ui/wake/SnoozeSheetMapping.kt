package com.yawnandpawn.app.ui.wake

import androidx.compose.runtime.staticCompositionLocalOf
import com.yawnandpawn.app.core.billing.FeeLadder
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.followingProduct
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SnoozeAvailability
import kotlin.time.TimeSource
import com.yawnandpawn.app.core.session.SnoozeOffer as CoreSnoozeOffer

/**
 * What opened `sheet-snooze-confirm` (Story 4.13), for one ring ([ringIndex]) of one session ([sessionId]): the
 * engine's `ShowSnoozeConfirm` ([Confirm]) or the coordinator's `ShowReuseSheet` ([AlreadyPaid]). A request for another
 * session or ring is stale and shows nothing.
 */
sealed interface SheetRequest {
    val sessionId: String
    val ringIndex: Int

    /** `ShowSnoozeConfirm(offer)`: confirm paying for [offer]. */
    data class Confirm(
        override val sessionId: String,
        override val ringIndex: Int,
        val offer: CoreSnoozeOffer,
    ) : SheetRequest

    /** `ShowReuseSheet(productId)`: a stranded payment for [productId] can pay for this snooze. */
    data class AlreadyPaid(
        override val sessionId: String,
        override val ringIndex: Int,
        val productId: String,
    ) : SheetRequest
}

/**
 * Whether [this] request still belongs on [session]'s screen: the same session and ring, and for a confirm, snooze is
 * still Available ([availability]) for the same snooze ([sameSnooze]: product and snooze number). When not, the sheet
 * closes for good (Story 4.13: "Pay" while it just became unavailable): the snooze button underneath then shows the
 * reason. Pure.
 */
fun SheetRequest.stillWanted(
    session: SessionData,
    availability: SnoozeAvailability,
): Boolean = unwantedBecause(session, availability) == null

/**
 * Why [this] request no longer belongs on [session]'s screen with [availability], for the log (no personal data), or
 * null while it is still wanted ([stillWanted]). Pure.
 */
fun SheetRequest.unwantedBecause(
    session: SessionData,
    availability: SnoozeAvailability,
): String? =
    when {
        sessionId != session.sessionId -> {
            "another session"
        }

        ringIndex != session.ringIndex -> {
            "another ring"
        }

        this is SheetRequest.Confirm && availability is SnoozeAvailability.Unavailable -> {
            "snooze unavailable: ${availability.reason}"
        }

        this is SheetRequest.Confirm && availability is SnoozeAvailability.Available && !availability.offer.sameSnooze(offer) -> {
            "another snooze on sale"
        }

        else -> {
            null
        }
    }

/**
 * Whether [this] offer sells the same snooze as [other]: the same product and snooze number. The cached price an offer
 * carries ([CoreSnoozeOffer.price]) is display data, not part of what is sold: it changes each time the price cache is
 * written, and every live price Play gives is written with a new fetch time (Story 4.12). Comparing it closed the sheet a
 * frame after it opened, as soon as the live price asked at open came back (Story 4.18 device check).
 */
fun CoreSnoozeOffer.sameSnooze(other: CoreSnoozeOffer): Boolean = productId == other.productId && snoozeNumber == other.snoozeNumber

/**
 * The price [request] shows, from [priceOf] (live first, then the cached display price); for a confirm, else the cached
 * price its offer carried when the engine opened it (the price the snooze button showed). Null when nobody knows one.
 */
fun requestPrice(
    request: SheetRequest,
    priceOf: PriceLookup,
): Money? =
    when (request) {
        is SheetRequest.Confirm -> priceOf(request.offer.productId) ?: request.offer.price?.price
        is SheetRequest.AlreadyPaid -> priceOf(request.productId)
    }

/**
 * The sheet [session]'s wake screen shows (Story 4.13), or null for none. Pure.
 * - While the unlock before Play is pending (`paying` with `unlocking`): *unlocking* at [unlockingPrice] (the live price
 *   the Pay tap sent). While billing is in flight without it, none: Play's sheet is on top and its result closes ours.
 * - A [SheetRequest.Confirm] still wanted: *confirm* at [requestPrice] (live first, then the cached display price, then
 *   the cached price the offer carried), "The next one costs {nextPrice}." with the price of the following snooze
 *   ([FeeLadder.followingProduct]) when there is one and it is known, else only "This one costs {price}.", and the tax
 *   note when [showTaxNote].
 * - A [SheetRequest.AlreadyPaid]: *already paid* at the price of its product.
 * A price nobody knows shows no sheet rather than an invented one, and never silently: the host closes a confirm with
 * "No connection. No charge." and declines an already-paid one, whose only text ("You already paid {price} earlier…")
 * names the price (`ConfirmSheetHost.follow`).
 */
@Suppress("LongParameterList") // Every input of the sheet; each is one plain value.
fun snoozeSheet(
    request: SheetRequest?,
    session: SessionData,
    availability: SnoozeAvailability,
    priceOf: PriceLookup,
    ladder: FeeLadder,
    showTaxNote: Boolean,
    unlockingPrice: Money?,
): SnoozeSheet? =
    when {
        session.paying != null -> {
            if (session.unlocking) unlockingPrice?.let(SnoozeSheet::Unlocking) else null
        }

        request == null || !request.stillWanted(session, availability) -> {
            null
        }

        else -> {
            shownRequest(request, session, priceOf, ladder, showTaxNote)
        }
    }

/** The *confirm* or *already paid* sheet for a [request] that is still wanted, at the prices [priceOf] knows. */
private fun shownRequest(
    request: SheetRequest,
    session: SessionData,
    priceOf: PriceLookup,
    ladder: FeeLadder,
    showTaxNote: Boolean,
): SnoozeSheet? =
    requestPrice(request, priceOf)?.let { price ->
        when (request) {
            is SheetRequest.Confirm -> {
                SnoozeSheet.Confirm(
                    minutes = session.config.snoozeLengthMinutes,
                    price = price,
                    nextPrice = ladder.followingProduct(session, request.offer)?.let(priceOf),
                    showTaxNote = showTaxNote,
                )
            }

            is SheetRequest.AlreadyPaid -> {
                SnoozeSheet.AlreadyPaid(price)
            }
        }
    }

/**
 * The 500 ms anti-double-tap guard of `sheet-snooze-confirm` (EXPERIENCE.md, FR-RNG-5): input is accepted only once
 * [INPUT_LOCK_MILLIS] have passed on the monotonic clock [now] (milliseconds) since it was made or [arm]ed, whatever
 * the animation setting. The sheet makes a new one each time it opens or its state or displayed price changes.
 */
class InputGuard(
    private val now: () -> Long,
) {
    private var armedAt: Long = now()

    /** Starts the 500 ms again. */
    fun arm() {
        armedAt = now()
    }

    /** Whether a tap now counts. */
    fun accepts(): Boolean = now() - armedAt >= INPUT_LOCK_MILLIS
}

/** Kotlin's monotonic time source, the fallback when no clock is provided. */
private val sinceStart = TimeSource.Monotonic.markNow()

/**
 * The monotonic clock (milliseconds) the sheet's [InputGuard] reads. `WakeActivity` and the design preview provide the
 * app's `MonotonicClock` (time ports, AD-3); tests may provide their own. Unprovided, Kotlin's monotonic source.
 */
val LocalWakeClock = staticCompositionLocalOf<() -> Long> { { sinceStart.elapsedNow().inWholeMilliseconds } }

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
 * still Available ([availability]) for the same offer. When not, the sheet closes for good (Story 4.13: "Pay" while it
 * just became unavailable): the snooze button underneath then shows the reason. Pure.
 */
fun SheetRequest.stillWanted(
    session: SessionData,
    availability: SnoozeAvailability,
): Boolean {
    if (sessionId != session.sessionId || ringIndex != session.ringIndex) return false
    return when (this) {
        is SheetRequest.Confirm -> (availability as? SnoozeAvailability.Available)?.offer == offer
        is SheetRequest.AlreadyPaid -> true
    }
}

/**
 * The sheet [session]'s wake screen shows (Story 4.13), or null for none. Pure.
 * - While the unlock before Play is pending (`paying` with `unlocking`): *unlocking* at [unlockingPrice] (the live price
 *   the Pay tap sent). While billing is in flight without it, none: Play's sheet is on top and its result closes ours.
 * - A [SheetRequest.Confirm] still wanted: *confirm* at [priceOf] the offer (live first, then the cached display price),
 *   "The next one costs {nextPrice}." with the price of the following snooze ([FeeLadder.followingProduct]) when there is
 *   one and it is known, else only "This one costs {price}.", and the tax note when [showTaxNote].
 * - A [SheetRequest.AlreadyPaid]: *already paid* at the price of its product.
 * A price nobody knows shows no sheet rather than an invented one.
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
    when (request) {
        is SheetRequest.Confirm -> {
            priceOf(request.offer.productId)?.let { price ->
                SnoozeSheet.Confirm(
                    minutes = session.config.snoozeLengthMinutes,
                    price = price,
                    nextPrice = ladder.followingProduct(session, request.offer)?.let(priceOf),
                    showTaxNote = showTaxNote,
                )
            }
        }

        is SheetRequest.AlreadyPaid -> {
            priceOf(request.productId)?.let(SnoozeSheet::AlreadyPaid)
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

package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.UnavailableReason
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Looks up the price of a Play snooze product (`snooze_usd_NN`); null while the catalogue is not loaded. */
typealias PriceLookup = (productId: String) -> Money?

/** The Epic 1 [PriceLookup]: there is no billing yet, so no price is known. */
val NoPrices: PriceLookup = { null }

/**
 * The Ringing screen for [session] (Story 1.15): the alarm's time and date in [zone] (`config.scheduledAt`, as in the
 * notification), its label (none when blank), its note ([wakeNote]: a paused call, or the Direct Boot check), and the snooze that
 * [availability] (the `SnoozeAvailabilityPolicy` result) allows. The session line needs the paid amount, which arrives
 * with Money in Epic 4, so it is not shown yet. Pure.
 */
fun ringingUiState(
    session: SessionData,
    availability: SnoozeAvailability,
    zone: TimeZone,
    priceOf: PriceLookup = NoPrices,
): RingingUiState {
    val at = session.config.scheduledAt.toLocalDateTime(zone)
    return RingingUiState(
        time = at.time,
        date = at.date,
        label = session.config.label?.takeIf { it.isNotBlank() },
        snooze = snoozeOffer(availability, session, priceOf),
        note = wakeNote(session),
    )
}

/**
 * The Ringing screen of an emergency ring (no session could start, NFR-2) or of a wake screen opened just before the
 * session, for the alarm at [alarmAt]: no label, and snooze waits for prices like any Epic 1 ring. Pure.
 */
fun alarmOnlyRingingUiState(
    alarmAt: Instant,
    zone: TimeZone,
): RingingUiState {
    val at = alarmAt.toLocalDateTime(zone)
    return RingingUiState(time = at.time, date = at.date, snooze = SnoozeOffer.Unavailable(SnoozeUnavailableReason.PricesNotLoaded))
}

/**
 * What `button-snooze` shows for [availability] (AD-7). A price [priceOf] does not know means the catalogue is not
 * loaded, so it reads "prices not loaded yet" rather than inventing one.
 */
fun snoozeOffer(
    availability: SnoozeAvailability,
    session: SessionData,
    priceOf: PriceLookup = NoPrices,
): SnoozeOffer =
    when (availability) {
        is SnoozeAvailability.Available -> priceOf(availability.offer.productId)?.let { SnoozeOffer.Available(it) } ?: pricesNotLoaded()
        is SnoozeAvailability.Unavailable -> unavailableOffer(availability.reason, session, priceOf)
    }

private fun unavailableOffer(
    reason: UnavailableReason,
    session: SessionData,
    priceOf: PriceLookup,
): SnoozeOffer =
    when (reason) {
        UnavailableReason.TestMode -> {
            SnoozeOffer.TestMode
        }

        UnavailableReason.BeforeFirstUnlock -> {
            SnoozeOffer.LockedBeforeUnlock
        }

        // A damaged frozen fee (logged by the policy) has no copy of its own; it reads like prices not loaded.
        UnavailableReason.CatalogueNotLoaded, UnavailableReason.InvalidFee -> {
            pricesNotLoaded()
        }

        UnavailableReason.Offline -> {
            SnoozeOffer.Unavailable(SnoozeUnavailableReason.Offline)
        }

        UnavailableReason.MaxSnoozesReached -> {
            SnoozeOffer.Unavailable(SnoozeUnavailableReason.MaxSnoozesReached)
        }

        UnavailableReason.PriceCapReached -> {
            SnoozeOffer.Unavailable(SnoozeUnavailableReason.PriceCapReached)
        }

        UnavailableReason.PaymentPending -> {
            SnoozeOffer.Unavailable(SnoozeUnavailableReason.PaymentPending)
        }

        UnavailableReason.EarlierPaymentRefunding -> {
            session.declinedReuseProduct?.let(priceOf)?.let { SnoozeOffer.StrandedRefund(it) } ?: pricesNotLoaded()
        }
    }

private fun pricesNotLoaded(): SnoozeOffer = SnoozeOffer.Unavailable(SnoozeUnavailableReason.PricesNotLoaded)

/** Which placeholder check step the wake screen answered: one answer per session, ring and step. */
data class PlaceholderStep(
    val sessionId: String,
    val ringIndex: Int,
    val step: Int,
)

/**
 * The Epic 1 placeholder check step [state] waits for, or null: Grace or Loud with a `CheckType.Placeholder` current
 * step. The wake screen answers it with `CheckAnswer.Placeholder`, so "I'm up" alone ends the session; Epic 3 shows
 * the real check instead.
 */
fun placeholderStepDue(state: SessionState): PlaceholderStep? {
    val session =
        when (state) {
            is SessionState.Grace -> state.session
            is SessionState.Loud -> state.session
            else -> null
        }
    return session
        ?.takeIf { it.checkRun.currentEntry?.type == CheckType.Placeholder }
        ?.let { PlaceholderStep(it.sessionId, it.ringIndex, it.checkRun.step.entry) }
}

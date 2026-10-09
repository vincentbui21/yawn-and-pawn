package com.yawnandpawn.app.ui

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.UsdFeeLadder
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.SnoozeOffer
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.ui.wake.CheckUiState
import com.yawnandpawn.app.ui.wake.PriceLookup
import com.yawnandpawn.app.ui.wake.RingingUiState
import com.yawnandpawn.app.ui.wake.SheetRequest
import com.yawnandpawn.app.ui.wake.SnoozeSheet
import com.yawnandpawn.app.ui.wake.snoozeSheet
import com.yawnandpawn.app.ui.wake.SnoozeOffer as UiSnoozeOffer

/**
 * Story 4.13: the confirm sheet's states as `WakeActivity` maps them (through `snoozeSheet`, USD prices B × N), over the
 * Ringing and Check samples with snooze on sale.
 */
object SnoozeSheetSamples {
    private val usd: PriceLookup = { id -> Money.of(id.takeLast(2).toInt(), "USD") }
    private val dollar = Money.of(1, "USD")

    private fun confirm(
        snoozesGranted: Int = 0,
        taxNote: Boolean = false,
    ): SnoozeSheet {
        val session: SessionData = aSession().copy(snoozesGranted = snoozesGranted)
        val offer = SnoozeOffer("snooze_usd_0${snoozesGranted + 1}", snoozesGranted + 1)
        val request = SheetRequest.Confirm(session.sessionId, session.ringIndex, offer)
        return checkNotNull(snoozeSheet(request, session, SnoozeAvailability.Available(offer), usd, UsdFeeLadder, taxNote, null))
    }

    /** "Snooze for 9 min?", $1.00, "This one costs $1.00. The next one costs $2.00.", the nudge. */
    val confirm: SnoozeSheet = confirm()

    /** The same with the tax note (a US or Canadian Play billing country). */
    val confirmTax: SnoozeSheet = confirm(taxNote = true)

    /** Snooze 5 of 5: "This one costs $5.00." only. */
    val lastSnooze: SnoozeSheet = confirm(snoozesGranted = 4)

    /** "Pay" on a locked phone: "Unlock to pay $1.00" and "Cancel". */
    val unlocking: SnoozeSheet = SnoozeSheet.Unlocking(dollar)

    /** A stranded $1.00 payment: "Use it" / "Not now". */
    val alreadyPaid: SnoozeSheet = SnoozeSheet.AlreadyPaid(dollar)

    /** The Ringing screen with snooze on sale and [sheet] over it. */
    fun overRinging(sheet: SnoozeSheet): RingingUiState = RingingSamples.enabledSnooze.copy(sheet = sheet)

    /** The Math check in Grace with snooze on sale in its footer and [sheet] over it. */
    fun overCheck(sheet: SnoozeSheet): CheckUiState = CheckSamples.grace.copy(snooze = UiSnoozeOffer.Available(dollar), sheet = sheet)
}

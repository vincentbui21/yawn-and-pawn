package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceEntry
import com.yawnandpawn.app.core.billing.UsdFeeLadder
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.UnavailableReason
import com.yawnandpawn.app.testing.DEFAULT_FAKE_INSTANT
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import com.yawnandpawn.app.core.session.SnoozeOffer as CoreSnoozeOffer

/** Story 4.13: which confirm sheet a session's wake screen shows, and the 500 ms guard. */
class SnoozeSheetMappingTest {
    private val usd: PriceLookup = { id -> Money.of(id.takeLast(2).toInt(), "USD") }
    private val first = CoreSnoozeOffer("snooze_usd_01", 1)
    private val session = aSession()
    private val available = SnoozeAvailability.Available(first)
    private val confirm = SheetRequest.Confirm(session.sessionId, session.ringIndex, first)

    private fun sheet(
        request: SheetRequest? = confirm,
        on: SessionData = session,
        availability: SnoozeAvailability = available,
        priceOf: PriceLookup = usd,
        taxNote: Boolean = false,
        unlockingPrice: Money? = null,
    ) = snoozeSheet(request, on, availability, priceOf, UsdFeeLadder, taxNote, unlockingPrice)

    @Test
    fun `confirm shows the frozen snooze length, the price and the next price`() {
        assertEquals(SnoozeSheet.Confirm(minutes = 9, price = Money.of(1, "USD"), nextPrice = Money.of(2, "USD")), sheet())
        assertEquals(true, (sheet(taxNote = true) as SnoozeSheet.Confirm).showTaxNote)
    }

    @Test
    fun `the last snooze allowed has no next price`() {
        val last = CoreSnoozeOffer("snooze_usd_05", 5)
        val fifth = session.copy(snoozesGranted = 4)
        val shown = sheet(SheetRequest.Confirm(fifth.sessionId, fifth.ringIndex, last), fifth, SnoozeAvailability.Available(last))
        assertEquals(SnoozeSheet.Confirm(minutes = 9, price = Money.of(5, "USD"), nextPrice = null), shown)
    }

    @Test
    fun `an unknown next price shows the short body rather than an invented one`() {
        val onlyFirst: PriceLookup = { id -> if (id == "snooze_usd_01") Money.of(1, "USD") else null }
        assertNull((sheet(priceOf = onlyFirst) as SnoozeSheet.Confirm).nextPrice)
    }

    @Test
    fun `no sheet without a request, with an unknown price, or for another session or ring`() {
        assertNull(sheet(request = null))
        assertNull(sheet(priceOf = { null }))
        assertNull(sheet(request = confirm.copy(sessionId = "other")))
        assertNull(sheet(request = confirm.copy(ringIndex = 2)))
    }

    @Test
    fun `a confirm closes once snooze is unavailable or offers another snooze`() {
        assertNull(sheet(availability = SnoozeAvailability.Unavailable(UnavailableReason.Offline)))
        assertNull(sheet(availability = SnoozeAvailability.Available(CoreSnoozeOffer("snooze_usd_02", 2))))
        assertFalse(confirm.stillWanted(session, SnoozeAvailability.Unavailable(UnavailableReason.PaymentPending)))
        assertTrue(confirm.stillWanted(session, available))
    }

    @Test
    fun `4_18 device check - a confirm stays wanted when the cached price its offer carries is written again or changes`() {
        val cached = PriceEntry("snooze_usd_01", "€1.09", Money(1_090_000, "EUR"), DEFAULT_FAKE_INSTANT)
        val opened = confirm.copy(offer = first.copy(price = cached))
        // Play's live price at open is stored with a new fetch time; a refresh may also change the amount.
        val restored = SnoozeAvailability.Available(first.copy(price = cached.copy(fetchedAt = DEFAULT_FAKE_INSTANT + 1.minutes)))
        val repriced =
            SnoozeAvailability.Available(
                first.copy(price = cached.copy(formattedPrice = "€1.19", price = Money(1_190_000, "EUR"))),
            )

        assertTrue(opened.stillWanted(session, restored))
        assertTrue(opened.stillWanted(session, repriced))
        assertTrue(opened.stillWanted(session, available), "an offer with no price attached sells the same snooze")
        assertEquals(Money.of(1, "USD"), (sheet(request = opened, availability = restored) as SnoozeSheet.Confirm).price)
        assertNull(opened.unwantedBecause(session, repriced))
        assertEquals(
            "another snooze on sale",
            opened.unwantedBecause(session, SnoozeAvailability.Available(CoreSnoozeOffer("snooze_usd_02", 2))),
        )
        assertEquals(
            "snooze unavailable: Offline",
            opened.unwantedBecause(session, SnoozeAvailability.Unavailable(UnavailableReason.Offline)),
        )
        assertEquals("another ring", opened.unwantedBecause(session.copy(ringIndex = 2), restored))
    }

    @Test
    fun `a confirm whose product nobody prices shows the cached price its offer carried`() {
        val cached = PriceEntry("snooze_usd_01", "€1.09", Money(1_090_000, "EUR"), DEFAULT_FAKE_INSTANT)
        val opened = confirm.copy(offer = first.copy(price = cached))
        assertEquals(Money(1_090_000, "EUR"), (sheet(request = opened, priceOf = { null }) as SnoozeSheet.Confirm).price)
        assertEquals(Money(1_090_000, "EUR"), requestPrice(opened) { null })
        assertNull(requestPrice(confirm) { null })
    }

    @Test
    fun `while the unlock is pending the sheet asks to unlock at the sent price, and while Play is open it shows none`() {
        val unlocking = session.copy(paying = PurchaseIntentId("intent-1"), unlocking = true)
        assertEquals(SnoozeSheet.Unlocking(Money.of(1, "USD")), sheet(on = unlocking, unlockingPrice = Money.of(1, "USD")))
        assertEquals(SnoozeSheet.Unlocking(Money.of(1, "USD")), sheet(request = null, on = unlocking, unlockingPrice = Money.of(1, "USD")))
        assertNull(sheet(on = unlocking, unlockingPrice = null))
        assertNull(sheet(on = session.copy(paying = PurchaseIntentId("intent-1"))), "billing in flight: Play's sheet is on top")
    }

    @Test
    fun `already paid shows the stranded product's price whatever the availability`() {
        val reuse = SheetRequest.AlreadyPaid(session.sessionId, session.ringIndex, "snooze_usd_03")
        assertEquals(
            SnoozeSheet.AlreadyPaid(Money.of(3, "USD")),
            sheet(reuse, availability = SnoozeAvailability.Unavailable(UnavailableReason.Offline)),
        )
        assertTrue(reuse.stillWanted(session, SnoozeAvailability.Unavailable(UnavailableReason.Offline)))
        assertNull(sheet(reuse.copy(ringIndex = 3)))
    }

    @Test
    fun `a grace window that ends keeps the sheet open (Grace and Loud share the ring)`() {
        val graceTest = aSession(config = aSessionConfig())
        assertEquals(sheet(on = graceTest), sheet(on = graceTest.copy(graceEnd = null)))
    }

    @Test
    fun `the guard ignores input until 500 ms on the monotonic clock, and again after it is armed`() {
        var now = 1_000L
        val guard = InputGuard { now }
        now += 499
        assertFalse(guard.accepts(), "499 ms")
        now += 1
        assertTrue(guard.accepts(), "500 ms")
        guard.arm()
        now += 499
        assertFalse(guard.accepts(), "499 ms after a change")
        now += 1
        assertTrue(guard.accepts(), "500 ms after a change")
    }
}

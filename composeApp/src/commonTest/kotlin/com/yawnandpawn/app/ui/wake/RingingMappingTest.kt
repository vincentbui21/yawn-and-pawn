package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceEntry
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.session.NoBillingSnoozeAvailability
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.StepPointer
import com.yawnandpawn.app.core.session.UnavailableReason
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.testing.everySessionState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import com.yawnandpawn.app.core.session.SnoozeOffer as CoreSnoozeOffer

/** Story 1.15: the session → Ringing screen mapping. */
class RingingMappingTest {
    private val utc = TimeZone.UTC
    private val dollar = Money.of(1, "USD")
    private val prices: PriceLookup = { productId -> if (productId == "snooze_usd_01") dollar else null }

    private fun unavailable(reason: UnavailableReason) = SnoozeAvailability.Unavailable(reason)

    @Test
    fun `a first ring shows the alarm time and date in the zone, its label and prices not loaded yet`() {
        val session = aSession()

        val state = ringingUiState(session, NoBillingSnoozeAvailability().availability(session), TimeZone.of("Europe/Berlin"))

        // 2027-03-03T06:00Z is 07:00 in Berlin (CET).
        assertEquals(LocalTime(7, 0), state.time)
        assertEquals(LocalDate(2027, 3, 3), state.date)
        assertEquals("Work", state.label)
        assertEquals(SnoozeOffer.Unavailable(SnoozeUnavailableReason.PricesNotLoaded), state.snooze)
        assertNull(state.note)
        assertNull(state.sessionLine)
        assertNull(state.sheet)
        assertNull(state.message)
    }

    @Test
    fun `the date follows the zone across midnight`() {
        val session = aSession(config = aSessionConfig(scheduledAt = Instant.parse("2027-03-03T23:30:00Z")))

        val state = ringingUiState(session, unavailable(UnavailableReason.CatalogueNotLoaded), TimeZone.of("Asia/Tokyo"))

        assertEquals(LocalTime(8, 30), state.time)
        assertEquals(LocalDate(2027, 3, 4), state.date)
    }

    @Test
    fun `a null or blank label shows no label`() {
        listOf(null, "", "   ").forEach { label ->
            val session = aSession(config = aSessionConfig(label = label))

            assertNull(ringingUiState(session, unavailable(UnavailableReason.CatalogueNotLoaded), utc).label, "label '$label'")
        }
    }

    @Test
    fun `a test session reads Test no charge`() {
        val session = aSession(config = aSessionConfig(testMode = true))

        assertEquals(SnoozeOffer.TestMode, ringingUiState(session, NoBillingSnoozeAvailability().availability(session), utc).snooze)
    }

    @Test
    fun `every unavailable reason maps to its snooze label`() {
        val expected =
            mapOf(
                UnavailableReason.TestMode to SnoozeOffer.TestMode,
                UnavailableReason.CatalogueNotLoaded to SnoozeOffer.Unavailable(SnoozeUnavailableReason.PricesNotLoaded),
                UnavailableReason.Offline to SnoozeOffer.Unavailable(SnoozeUnavailableReason.Offline),
                UnavailableReason.MaxSnoozesReached to SnoozeOffer.Unavailable(SnoozeUnavailableReason.MaxSnoozesReached),
                UnavailableReason.PriceCapReached to SnoozeOffer.Unavailable(SnoozeUnavailableReason.PriceCapReached),
                UnavailableReason.PaymentPending to SnoozeOffer.Unavailable(SnoozeUnavailableReason.PaymentPending),
                UnavailableReason.BeforeFirstUnlock to SnoozeOffer.LockedBeforeUnlock,
                UnavailableReason.EarlierPaymentRefunding to SnoozeOffer.StrandedRefund(),
                UnavailableReason.InvalidFee to SnoozeOffer.Unavailable(SnoozeUnavailableReason.PricesNotLoaded),
            )

        assertEquals(UnavailableReason.entries.toSet(), expected.keys, "every reason is covered")
        expected.forEach { (reason, offer) -> assertEquals(offer, snoozeOffer(unavailable(reason), prices), "$reason") }
    }

    @Test
    fun `an available snooze shows its price, and an unknown price reads prices not loaded yet`() {
        assertEquals(SnoozeOffer.Available(dollar), snoozeOffer(SnoozeAvailability.Available(CoreSnoozeOffer("snooze_usd_01", 1)), prices))
        assertEquals(
            SnoozeOffer.Unavailable(SnoozeUnavailableReason.PricesNotLoaded),
            snoozeOffer(SnoozeAvailability.Available(CoreSnoozeOffer("snooze_usd_02", 2)), prices),
        )
        assertEquals(
            SnoozeOffer.Unavailable(SnoozeUnavailableReason.PricesNotLoaded),
            snoozeOffer(SnoozeAvailability.Available(CoreSnoozeOffer("snooze_usd_01", 1))),
        )
    }

    @Test
    fun `the policy's price wins, shown as Play's own string (Story 4_7)`() {
        val euro = PriceEntry("snooze_usd_02", "2,49 €", Money(2_490_000, "EUR"), Instant.parse("2027-03-03T05:00:00Z"))

        assertEquals(
            SnoozeOffer.Available(euro.price, "2,49 €"),
            snoozeOffer(SnoozeAvailability.Available(CoreSnoozeOffer("snooze_usd_02", 2, euro)), prices),
        )
    }

    @Test
    fun `a refund names the amount actually paid, or no amount, never today's cached price`() {
        val paid = Money(1_290_000, "EUR")

        assertEquals(
            SnoozeOffer.StrandedRefund(paid),
            snoozeOffer(SnoozeAvailability.Unavailable(UnavailableReason.EarlierPaymentRefunding, refunding = paid), prices),
        )
        assertEquals(SnoozeOffer.StrandedRefund(null), snoozeOffer(unavailable(UnavailableReason.EarlierPaymentRefunding), prices))
    }

    @Test
    fun `a call pausing the ring shows the phone call note`() {
        val paused = aSession().copy(pausedAt = TimeSnapshot(0, 0, 1))

        assertEquals(WakeNote.PhoneCall, ringingUiState(paused, unavailable(UnavailableReason.CatalogueNotLoaded), utc).note)
    }

    @Test
    fun `an alarm without a session shows its time with no label and prices not loaded yet`() {
        val state = alarmOnlyRingingUiState(Instant.parse("2027-03-03T06:15:00Z"), utc)

        assertEquals(LocalTime(6, 15), state.time)
        assertEquals(LocalDate(2027, 3, 3), state.date)
        assertNull(state.label)
        assertEquals(SnoozeOffer.Unavailable(SnoozeUnavailableReason.PricesNotLoaded), state.snooze)
    }

    @Test
    fun `the placeholder step is due in Grace and Loud only`() {
        val session = aSession()
        val due = PlaceholderStep(session.sessionId, ringIndex = 1, step = 0)

        everySessionState(session).forEach { state ->
            val expected = if (state is SessionState.Grace || state is SessionState.Loud) due else null
            assertEquals(expected, placeholderStepDue(state), state::class.simpleName)
        }
    }

    @Test
    fun `no placeholder step is due once every step is passed`() {
        val passed = aSession().let { it.copy(checkRun = it.checkRun.copy(step = StepPointer(1, 0))) }
        val empty = aSession().let { it.copy(checkRun = it.checkRun.copy(plan = CheckPlan(CheckMode.All, emptyList()))) }
        val math = CheckPlan(CheckMode.All, listOf(CheckEntry(CheckType.Math, Difficulty.Medium, count = 3)))
        val realCheck = aSession().let { it.copy(checkRun = it.checkRun.copy(plan = math)) }

        assertNull(placeholderStepDue(SessionState.Loud(passed)))
        assertNull(placeholderStepDue(SessionState.Grace(empty)))
        assertNull(placeholderStepDue(SessionState.Grace(realCheck)), "a real check is not answered for the user")
    }
}

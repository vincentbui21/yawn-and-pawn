package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.UnavailableReason
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import com.yawnandpawn.app.core.session.SnoozeOffer as CoreSnoozeOffer

/** Story 4.15: the session line on a re-ring and the Snoozed screen's next ring time. */
class SessionLineMappingTest {
    private val usd1 = Money.of(1, "USD")
    private val usd2 = Money.of(2, "USD")
    private val eur = Money(1_190_000, "EUR")

    private fun available(number: Int) = SnoozeAvailability.Available(CoreSnoozeOffer("snooze_usd_0$number", number))

    @Test
    fun `the first ring shows no session line`() {
        assertNull(sessionLine(aSession()))
        assertNull(ringingUiState(aSession(), available(1), TimeZone.UTC).sessionLine)
    }

    @Test
    fun `a re-ring shows snooze n of the frozen max with every amount paid`() {
        val session = aSession().copy(snoozesGranted = 2, ringIndex = 3, paid = listOf(usd1, usd2))

        val state = ringingUiState(session, available(3), TimeZone.UTC)

        assertEquals(SessionLine(snoozeNumber = 2, maxSnoozes = 5, paid = listOf(usd1, usd2)), state.sessionLine)
    }

    @Test
    fun `amounts in two currencies are both kept for one total per currency`() {
        val session = aSession().copy(snoozesGranted = 2, paid = listOf(usd1, eur))

        assertEquals(listOf(usd1, eur), sessionLine(session)?.paid)
    }

    @Test
    fun `with max snoozes reached the line stays and the button says why`() {
        val session = aSession().copy(snoozesGranted = 5, paid = List(5) { usd1 })

        val state = ringingUiState(session, SnoozeAvailability.Unavailable(UnavailableReason.MaxSnoozesReached), TimeZone.UTC)

        assertEquals(SessionLine(5, 5, List(5) { usd1 }), state.sessionLine)
        assertEquals(SnoozeOffer.Unavailable(SnoozeUnavailableReason.MaxSnoozesReached), state.snooze)
    }

    @Test
    fun `no amount known or a test session shows no line`() {
        assertNull(sessionLine(aSession().copy(snoozesGranted = 1)), "never an invented amount")
        assertNull(sessionLine(aSession(config = aSessionConfig(testMode = true)).copy(snoozesGranted = 1, paid = listOf(usd1))))
    }

    @Test
    fun `a merged ring during a snooze keeps the same line`() {
        val snoozed = aSession().copy(snoozesGranted = 1, paid = listOf(usd1))
        // A merged alarm only moves the ring on (Epic 2): the count and the amounts stay, no fee is added.
        val merged = snoozed.copy(ringIndex = snoozed.ringIndex + 1)

        assertEquals(sessionLine(snoozed), sessionLine(merged))
    }

    @Test
    fun `Snoozed shows the snooze end's wall time in the zone`() {
        val start = TimeSnapshot(Instant.parse("2027-03-03T06:15:00Z").toEpochMilliseconds(), elapsedMillis = 0, bootCount = 1)
        val session = aSession().copy(snoozeEnd = Deadline.after(start, 9.minutes))

        assertEquals(SnoozedUiState(LocalTime(6, 24)), snoozedUiState(session, TimeZone.UTC))
        assertEquals(SnoozedUiState(LocalTime(7, 24)), snoozedUiState(session, TimeZone.of("Europe/Berlin")))
        assertNull(snoozedUiState(aSession(), TimeZone.UTC))
    }
}

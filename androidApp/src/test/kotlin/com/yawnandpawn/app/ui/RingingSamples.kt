package com.yawnandpawn.app.ui

import com.yawnandpawn.app.core.session.NoBillingSnoozeAvailability
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.TierFeeLadder
import com.yawnandpawn.app.core.session.UnavailableReason
import com.yawnandpawn.app.core.session.nextOffer
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.ui.format.Money
import com.yawnandpawn.app.ui.wake.RingingUiState
import com.yawnandpawn.app.ui.wake.ringingUiState
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * Ringing states built the way `WakeActivity` builds them: a session through the Story 1.15 mapper, with the Epic 1
 * snooze policy unless a case needs another result. A 06:15 UTC alarm, shown in UTC so every machine renders the same.
 */
object RingingSamples {
    private val utc = TimeZone.UTC
    private val at = Instant.parse("2027-03-03T06:15:00Z")

    private fun session(
        label: String? = "Work",
        testMode: Boolean = false,
    ): SessionData = aSession(config = aSessionConfig(label = label, scheduledAt = at, testMode = testMode))

    private fun epic1(session: SessionData): RingingUiState =
        ringingUiState(session, NoBillingSnoozeAvailability.availability(session), utc)

    /** A normal Epic 1 first ring: "Snooze unavailable: prices not loaded yet". */
    val firstRing: RingingUiState = epic1(session())

    val firstRingNoLabel: RingingUiState = epic1(session(label = null))

    /** Another unavailable reason (offline), as Epic 4's policy reports it. */
    val snoozeUnavailable: RingingUiState =
        session().let { ringingUiState(it, SnoozeAvailability.Unavailable(UnavailableReason.Offline), utc) }

    /** A test alarm: "Test · no charge". */
    val testAlarm: RingingUiState = epic1(session(label = null, testMode = true))

    /** The enabled "Snooze · {price}" variant, preview only until billing (Epic 4). */
    val enabledSnooze: RingingUiState =
        session().let { session ->
            ringingUiState(session, SnoozeAvailability.Available(TierFeeLadder.nextOffer(session)), utc) { Money.of(1, "USD") }
        }
}

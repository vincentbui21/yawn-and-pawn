package com.yawnandpawn.app.ui

import com.yawnandpawn.app.core.session.NoBillingSnoozeAvailability
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.TierFeeLadder
import com.yawnandpawn.app.core.session.UnavailableReason
import com.yawnandpawn.app.core.session.nextOffer
import com.yawnandpawn.app.testing.FakeUserLockState
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
        ringingUiState(session, NoBillingSnoozeAvailability().availability(session), utc)

    /** A normal Epic 1 first ring: "Prices not loaded yet" (TalkBack "Snooze unavailable, prices not loaded yet"). */
    val firstRing: RingingUiState = epic1(session())

    val firstRingNoLabel: RingingUiState = epic1(session(label = null))

    /** Another unavailable reason (offline), as Epic 4's policy reports it. */
    val snoozeUnavailable: RingingUiState =
        session().let { ringingUiState(it, SnoozeAvailability.Unavailable(UnavailableReason.Offline), utc) }

    /** A test alarm: "Test · no charge". */
    val testAlarm: RingingUiState = epic1(session(label = null, testMode = true))

    /** The Epic 1 policy while the phone is still locked after a reboot (Story 2.3). */
    private val locked = NoBillingSnoozeAvailability(FakeUserLockState(unlocked = false))

    /** Before the first unlock: lock icon, "Unlock your phone to snooze" (TalkBack "Snooze unavailable, Unlock ..."). */
    val lockedBeforeUnlock: RingingUiState = session().let { ringingUiState(it, locked.availability(it), utc) }

    /** Story 2.4: the same locked session after the user unlocked, with the Epic 1 policy: "Prices not loaded yet". */
    val afterUnlockPricesNotLoaded: RingingUiState =
        session().let { session ->
            val lock = FakeUserLockState(unlocked = false)
            val policy = NoBillingSnoozeAvailability(lock)
            lock.unlock()
            ringingUiState(session.copy(beforeFirstUnlock = true), policy.availability(session), utc)
        }

    /** Story 2.4: after the unlock with a policy that offers a snooze (fake, Epic 4's catalogue): "Snooze · {price}". */
    val afterUnlockSnooze: RingingUiState =
        session().copy(beforeFirstUnlock = true).let { session ->
            ringingUiState(session, SnoozeAvailability.Available(TierFeeLadder.nextOffer(session)), utc) { Money.of(1, "USD") }
        }

    /** A test alarm before the first unlock still says "Test · no charge". */
    val testAlarmLocked: RingingUiState = session(label = null, testMode = true).let { ringingUiState(it, locked.availability(it), utc) }

    /** The enabled "Snooze · {price}" variant, preview only until billing (Epic 4). */
    val enabledSnooze: RingingUiState =
        session().let { session ->
            ringingUiState(session, SnoozeAvailability.Available(TierFeeLadder.nextOffer(session)), utc) { Money.of(1, "USD") }
        }
}

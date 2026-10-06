package com.yawnandpawn.app.android.screen

import com.yawnandpawn.app.android.wake.EmergencyRing
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.aSession
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Story 2.5: the app hands over to the wake screen only while the alarm rings (Snoozed waits for Story 2.6). */
class WakeScreenForwardingTest {
    private val session = aSession()

    @Test
    fun `it forwards in Ringing, Grace and Loud, and during an emergency ring, never otherwise`() {
        val expected =
            mapOf(
                SessionState.Idle to false,
                SessionState.Ringing(session) to true,
                SessionState.Grace(session) to true,
                SessionState.Loud(session) to true,
                SessionState.Snoozed(session) to false,
                SessionState.Completed(session) to false,
                SessionState.Missed(session) to false,
            )

        assertEquals(expected, expected.mapValues { (state, _) -> forwardsToWakeScreen(state, emergency = null) })
        val emergency = EmergencyRing(Instant.parse("2027-03-08T06:00:00Z"), volumePercent = 80)
        assertEquals(
            expected.mapValues { true },
            expected.mapValues { (state, _) -> forwardsToWakeScreen(state, emergency) },
        )
    }
}

package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.time.Deadline
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SessionSerializationTest {
    private val json = Json

    /** Every optional field set, so the round trip covers all of them. */
    private val full =
        ringSession(testConfig(checkPlan = TWO_STEPS)).copy(
            ringIndex = 3,
            snoozesGranted = 2,
            checkRun = CheckRun(TWO_STEPS, NEW_SEEDS, step = 1, failedAttempts = 4, fallbackUsed = true),
            paying = INTENT,
            noGraceThisRing = true,
            beforeFirstUnlock = true,
            paymentPending = true,
            declinedReuseProduct = PRODUCT,
            graceEnd = Deadline.after(T0, 20.seconds),
            snoozeEnd = Deadline.after(T0, 9.minutes),
            pausedAt = at(1.minutes),
        )

    @Test
    fun `every state variant survives a JSON round trip`() {
        val states =
            listOf(
                SessionState.Idle,
                SessionState.Ringing(full),
                SessionState.Grace(full),
                SessionState.Loud(full),
                SessionState.Snoozed(full),
                SessionState.Completed(full),
                SessionState.Missed(full),
                SessionState.Ringing(ringSession()),
            )
        states.forEach { state ->
            val encoded = json.encodeToString(SessionState.serializer(), state)
            assertEquals(state, json.decodeFromString(SessionState.serializer(), encoded), encoded)
        }
    }

    @Test
    fun `the encoded state names its variant and keeps the scheduled time as an instant`() {
        val encoded = json.encodeToString(SessionState.serializer(), SessionState.Snoozed(ringSession()))
        assertEquals(true, "\"type\":\"Snoozed\"" in encoded, encoded)
        assertEquals(true, "2027-03-03T06:00:00Z" in encoded, encoded)
    }
}

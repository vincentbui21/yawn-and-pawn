package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.time.Deadline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SessionSerializationTest {
    private val json = SessionJson.json

    /** Every optional field set, so the round trip covers all of them. */
    private val full =
        ringSession(testConfig(checkPlan = MIXED)).copy(
            ringIndex = 3,
            snoozesGranted = 2,
            checkRun =
                CheckRun(
                    MIXED.copy(mode = CheckMode.All),
                    NEW_SEEDS,
                    step = StepPointer(1, 2),
                    failedAttempts = 4,
                    fallbackUsed = true,
                ),
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

    private companion object {
        /** A Random plan with a Math entry, so the round trip covers the plugin types. */
        val MIXED =
            CheckPlan(CheckMode.Random, listOf(CheckEntry(CheckType.Math, Difficulty.Hard, count = 4), CheckPlan.PLACEHOLDER_ENTRY))
    }
}

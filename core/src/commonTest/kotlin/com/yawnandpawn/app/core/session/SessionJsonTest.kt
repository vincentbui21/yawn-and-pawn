package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.time.Deadline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The stored session format (AD-2): [SessionJson] round trips and decodes committed version 1 rows. */
class SessionJsonTest {
    /** Every field set, matching [sessionV1] field for field. */
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
            // Version 1 deadlines have no creation time (Story 2.2 added it): it decodes as 0.
            graceEnd = Deadline.after(T0, 20.seconds).copy(createdElapsedMillis = 0),
            interactionDeadline = Deadline.after(T0, 30.minutes).copy(createdElapsedMillis = 0),
            snoozeEnd = Deadline.after(T0, 9.minutes).copy(createdElapsedMillis = 0),
            pausedAt = at(1.minutes),
        )

    private val activeStates: List<SessionState.Active> =
        listOf(
            SessionState.Ringing(full),
            SessionState.Grace(full),
            SessionState.Loud(full),
            SessionState.Snoozed(full),
            SessionState.Completed(full),
            SessionState.Missed(full),
        )

    @Test
    fun `the format ignores unknown keys, names the variant in type and writes defaults`() {
        val configuration = SessionJson.json.configuration
        assertTrue(configuration.ignoreUnknownKeys)
        assertEquals("type", configuration.classDiscriminator)
        assertTrue(configuration.encodeDefaults)

        val encoded = SessionJson.encode(SessionState.Ringing(ringSession()))
        assertTrue("\"type\":\"Ringing\"" in encoded, encoded)
        assertTrue("\"paying\":null" in encoded, "defaults are written: $encoded")
    }

    @Test
    fun `every state variant round trips through the session format`() {
        (listOf(SessionState.Idle, SessionState.Ringing(ringSession()), SessionState.Snoozed(snoozedSession())) + activeStates)
            .forEach { state ->
                val encoded = SessionJson.encode(state)
                assertEquals(StoredSession.Found(state), SessionJson.decode(encoded), encoded)
            }
    }

    @Test
    fun `the committed version 1 row of every active state decodes to that state`() {
        activeStates.forEach { state ->
            val name = state::class.simpleName!!
            assertEquals(StoredSession.Found(state), SessionJson.decode(v1(name)), name)
        }
        assertEquals(StoredSession.Found(SessionState.Idle), SessionJson.decode(IDLE_V1))
    }

    @Test
    fun `a version 1 row has no first ring, start boot state or end, and all three round trip`() {
        val decoded = assertIs<StoredSession.Found>(SessionJson.decode(v1("Completed"))).state
        val v1Session = assertIs<SessionState.Completed>(decoded).session
        assertEquals(Triple(null, false, null), Triple(v1Session.firstRing, v1Session.startedBeforeUnlock, v1Session.ended))

        val session = ringSession().copy(firstRing = at(5.seconds), startedBeforeUnlock = true, ended = at(3.minutes))
        val ringing = SessionState.Ringing(session)
        assertEquals(StoredSession.Found(ringing), SessionJson.decode(SessionJson.encode(ringing)))
    }

    @Test
    fun `a version 1 row with extra unknown fields still decodes`() {
        activeStates.forEach { state ->
            val name = state::class.simpleName!!
            val withExtras = v1(name, extraTop = ",\"addedLater\":42", extraSession = ",\"futureField\":{\"x\":[1,2]}")
            assertEquals(StoredSession.Found(state), SessionJson.decode(withExtras), name)
        }
    }

    @Test
    fun `undecodable text is unreadable and the reason names only the error type`() {
        listOf("{not json", "{\"type\":\"Dozing\",\"session\":{}}", "{\"type\":\"Ringing\"}", "").forEach { text ->
            val unreadable = assertIs<StoredSession.Unreadable>(SessionJson.decode(text), text)
            assertTrue("Work" !in unreadable.cause && "{" !in unreadable.cause, unreadable.cause)
        }
    }

    private companion object {
        const val IDLE_V1 = """{"type":"Idle"}"""

        /** The session every v1 fixture holds: all fields, as version 1 wrote them (defaults included). */
        const val SESSION_V1 =
            """{"sessionId":"session-1","config":{"alarmId":"alarm-1","label":"Work",""" +
                """"scheduledAt":"2027-03-03T06:00:00Z","testMode":false,"baseFeeTier":1,"maxSnoozes":5,""" +
                """"snoozeLengthMinutes":9,"graceSeconds":20,"vibrateInGrace":false,"volumePercent":80,""" +
                """"gradualVolume":true,"rampStartPercent":20,"soundRef":"builtin:default","vibration":true,""" +
                """"checkPlan":{"steps":[{"type":"Placeholder"},{"type":"Placeholder"}]}},"ringIndex":3,""" +
                """"snoozesGranted":2,"checkRun":{"plan":{"steps":[{"type":"Placeholder"},{"type":"Placeholder"}]},""" +
                """"seeds":[11,12],"step":1,"failedAttempts":4,"fallbackUsed":true},"paying":"intent-1",""" +
                """"noGraceThisRing":true,"beforeFirstUnlock":true,"paymentPending":true,""" +
                """"declinedReuseProduct":"snooze_usd_01",""" +
                """"graceEnd":{"wallMillis":1800000020000,"elapsedMillis":1020000,"bootCount":3},""" +
                """"interactionDeadline":{"wallMillis":1800001800000,"elapsedMillis":2800000,"bootCount":3},""" +
                """"snoozeEnd":{"wallMillis":1800000540000,"elapsedMillis":1540000,"bootCount":3},""" +
                """"pausedAt":{"wallMillis":1800000060000,"elapsedMillis":1060000,"bootCount":3}"""

        /** The version 1 row of state [type], optionally with fields a later version added. */
        fun v1(
            type: String,
            extraTop: String = "",
            extraSession: String = "",
        ): String = """{"type":"$type","session":$SESSION_V1$extraSession}$extraTop}"""
    }
}

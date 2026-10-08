package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.time.Deadline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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
            checkRun = CheckRun(TWO_STEPS, NEW_SEEDS, step = StepPointer(1, 0), failedAttempts = 4, fallbackUsed = true),
            paying = INTENT,
            noGraceThisRing = true,
            beforeFirstUnlock = true,
            // Version 1 has no Direct Boot ring flag (Story 2.4): it takes beforeFirstUnlock.
            directBootRing = true,
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
    fun `a fallback run keeps its source plan and the session's failed attempts through the format (Story 3_1 review)`() {
        val run = full.checkRun.copy(fallbackSource = CheckPlan(CheckMode.Random, TWO_STEPS.entries), totalFailedAttempts = 9)
        val state = SessionState.Loud(full.copy(checkRun = run))

        assertEquals(StoredSession.Found(state), SessionJson.decode(SessionJson.encode(state)))
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

    @Test
    fun `the committed version 2 row of every active state decodes to that state`() {
        val math = CheckEntry(CheckType.Math, Difficulty.Hard, count = 4)
        val v2Session =
            full.copy(
                config = full.config.copy(checkPlan = CheckPlan(CheckMode.Random, listOf(math, CheckPlan.PLACEHOLDER_ENTRY))),
                checkRun = CheckRun(CheckPlan(CheckMode.All, listOf(math)), listOf(11L), step = StepPointer(0, 2), failedAttempts = 4),
            )
        listOf(
            SessionState.Ringing(v2Session),
            SessionState.Grace(v2Session),
            SessionState.Loud(v2Session),
            SessionState.Snoozed(v2Session),
            SessionState.Completed(v2Session),
            SessionState.Missed(v2Session),
        ).forEach { state ->
            val name = state::class.simpleName!!
            val row = """{"type":"$name","session":$SESSION_V2}}"""
            assertEquals(StoredSession.Found(state), SessionJson.decode(row), name)
            assertEquals(StoredSession.Found(state), SessionJson.decode(SessionJson.encode(state)), "$name round trip")
        }
    }

    @Test
    fun `a row stored before Story 4_7 decodes with nothing paid, and the paid amounts round trip`() {
        val before = assertIs<StoredSession.Found>(SessionJson.decode("""{"type":"Snoozed","session":$SESSION_V2}}"""))
        assertEquals(emptyList(), (before.state as SessionState.Active).session.paid)

        val paid = SessionState.Snoozed(full.copy(paid = listOf(Money.of(1, "USD"), Money(2_490_000, "EUR"), Money(0, "JPY"))))
        val encoded = SessionJson.encode(paid)
        assertTrue(""""paid":[{"micros":1000000,"currency":"USD"},""" in encoded, encoded)
        assertEquals(StoredSession.Found(paid), SessionJson.decode(encoded))
    }

    @Test
    fun `a malformed paid amount is dropped, the session and the other amounts are kept`() {
        val state = SessionState.Snoozed(full.copy(paid = listOf(Money.of(1, "USD"), Money.of(2, "USD"))))
        val damaged = SessionJson.encode(state).replaceFirst(""""currency":"USD"""", """"currency":"usd"""")

        assertEquals(StoredSession.Found(SessionState.Snoozed(full.copy(paid = listOf(Money.of(2, "USD"))))), SessionJson.decode(damaged))
    }

    @Test
    fun `a partly broken or missing paid list never loses the session (review fix)`() {
        val state = SessionState.Snoozed(full.copy(paid = listOf(Money.of(2, "USD"))))
        val encoded = SessionJson.encode(state)
        val stored = """"paid":[{"micros":2000000,"currency":"USD"}]"""
        assertTrue(stored in encoded, encoded)
        val broken =
            listOf(
                """"paid":[{"micros":"x","currency":"USD"},{"micros":2000000,"currency":"USD"}]""" to listOf(Money.of(2, "USD")),
                """"paid":[{"currency":"USD"},{"micros":2000000},{"micros":2000000,"currency":"USD"}]""" to listOf(Money.of(2, "USD")),
                """"paid":[{"micros":1.5,"currency":"USD"},7,"USD",null,{"micros":2000000,"currency":5}]""" to emptyList(),
                """"paid":null""" to emptyList(),
                """"paid":"USD 2"""" to emptyList(),
                """"paid":{"micros":2000000,"currency":"USD"}""" to emptyList(),
            )
        broken.forEach { (paid, expected) ->
            val text = encoded.replace(stored, paid)
            assertEquals(StoredSession.Found(SessionState.Snoozed(full.copy(paid = expected))), SessionJson.decode(text), paid)
        }
    }

    @Test
    fun `a pending test config stored by version 1 decodes with its steps as placeholder entries`() {
        assertEquals(testConfig(checkPlan = TWO_STEPS), SessionJson.decodeConfig(CONFIG_V1))
        val config = testConfig(checkPlan = CheckPlan(CheckMode.Random, listOf(CheckEntry(CheckType.Math, Difficulty.Easy, count = 10))))
        assertEquals(config, SessionJson.decodeConfig(SessionJson.encodeConfig(config)))
        assertNull(SessionJson.decodeConfig("""{"checkPlan":{"steps":[]}}"""))
    }

    @Test
    fun `a version 1 row with a damaged check stays unreadable`() {
        listOf(
            v1("Loud").replace(""""step":1""", """"step":"one""""),
            v1("Loud").replace(""""steps":[{"type":"Placeholder"},{"type":"Placeholder"}]}}""", """"steps":"none"}}"""),
            v1(
                "Loud",
            ).replace(
                """{"steps":[{"type":"Placeholder"},{"type":"Placeholder"}]},""" + "\"seeds\"",
                """{"steps":[{"type":"Dozing"}]},"seeds"""",
            ),
        ).forEach { text ->
            assertTrue(text != v1("Loud"), "the fixture was changed")
            assertIs<StoredSession.Unreadable>(SessionJson.decode(text), text)
        }
    }

    private companion object {
        const val IDLE_V1 = """{"type":"Idle"}"""

        /** The config of every v1 fixture, as version 1 wrote it (also the stored pending test ring). */
        const val CONFIG_V1 =
            """{"alarmId":"alarm-1","label":"Work",""" +
                """"scheduledAt":"2027-03-03T06:00:00Z","testMode":false,"baseFeeTier":1,"maxSnoozes":5,""" +
                """"snoozeLengthMinutes":9,"graceSeconds":20,"vibrateInGrace":false,"volumePercent":80,""" +
                """"gradualVolume":true,"rampStartPercent":20,"soundRef":"builtin:default","vibration":true,""" +
                """"checkPlan":{"steps":[{"type":"Placeholder"},{"type":"Placeholder"}]}}"""

        /**
         * The session of every v2 fixture (Story 3.1): the plan as mode and entries, the run's step as a pointer. Otherwise
         * as [SESSION_V1].
         */
        const val SESSION_V2 =
            """{"sessionId":"session-1","config":{"alarmId":"alarm-1","label":"Work",""" +
                """"scheduledAt":"2027-03-03T06:00:00Z","testMode":false,"baseFeeTier":1,"maxSnoozes":5,""" +
                """"snoozeLengthMinutes":9,"graceSeconds":20,"vibrateInGrace":false,"volumePercent":80,""" +
                """"gradualVolume":true,"rampStartPercent":20,"soundRef":"builtin:default","vibration":true,""" +
                """"checkPlan":{"mode":"Random","entries":[{"type":{"type":"Math"},"difficulty":"Hard","count":4},""" +
                """{"type":{"type":"Placeholder"},"difficulty":"Medium","count":1}]}},"ringIndex":3,""" +
                """"snoozesGranted":2,"checkRun":{"plan":{"mode":"All","entries":""" +
                """[{"type":{"type":"Math"},"difficulty":"Hard","count":4}]},""" +
                """"seeds":[11],"step":{"entry":0,"item":2},"failedAttempts":4,"fallbackUsed":false},"paying":"intent-1",""" +
                """"noGraceThisRing":true,"beforeFirstUnlock":true,"paymentPending":true,""" +
                """"declinedReuseProduct":"snooze_usd_01",""" +
                """"graceEnd":{"wallMillis":1800000020000,"elapsedMillis":1020000,"bootCount":3},""" +
                """"interactionDeadline":{"wallMillis":1800001800000,"elapsedMillis":2800000,"bootCount":3},""" +
                """"snoozeEnd":{"wallMillis":1800000540000,"elapsedMillis":1540000,"bootCount":3},""" +
                """"pausedAt":{"wallMillis":1800000060000,"elapsedMillis":1060000,"bootCount":3}"""

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

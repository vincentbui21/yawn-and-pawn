package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Story 3.13: when Home suggests re-registering a camera check. No camera type exists before Story 3.10, so the
 * placeholder stands in for one ([cameraStandIn]), as `FakeCameraCheck` does.
 */
class ReRegisterSuggestionTest {
    private val now = Instant.parse("2027-03-10T07:00:00Z")
    private val cameraStandIn: (CheckType) -> Boolean = { it == CheckType.Placeholder }
    private val camera = CheckType.Placeholder

    private fun alarm(id: String) =
        Alarm(id = id, time = LocalTime(6, 0), requestCode = 1000, createdAt = now - 30.days, updatedAt = now - 30.days)

    private val alarmA = alarm("a")
    private val alarmB = alarm("b")
    private val registeredA = ConfiguredCheck("a", camera, registeredAt = now - 20.days)
    private val registeredB = ConfiguredCheck("b", camera, registeredAt = now - 20.days)

    private var sessions = 0

    /** A session of [alarmId] that rang [ago] before now and used the fallback for [from]. */
    private fun fallback(
        alarmId: String = "a",
        ago: Duration,
        from: String = camera.id,
        outcome: SessionOutcome = SessionOutcome.OnTime,
        used: Boolean = true,
    ) = SessionHistoryRow(
        sessionId = "s${++sessions}",
        alarmId = alarmId,
        scheduledAt = now - ago,
        firstRingAt = now - ago,
        endedAt = now - ago + 5.minutes,
        snoozeCount = 0,
        checkTypes = listOf("Math"),
        timeToCompleteMs = 300_000,
        fallbackUsed = used,
        directBoot = false,
        outcome = outcome,
        fallbackFrom = from.takeIf { used },
    )

    private fun suggest(
        history: List<SessionHistoryRow>,
        checks: List<ConfiguredCheck> = listOf(registeredA, registeredB),
        dismissedAt: Map<CheckKey, Instant> = emptyMap(),
        alarms: List<Alarm> = listOf(alarmA, alarmB),
    ) = reRegisterSuggestion(history, alarms, checks, now, dismissedAt, cameraStandIn)

    /** The suggestion for the camera check of [alarmId]: [fallbacks] counted, the newest [newestAgo] before now. */
    private fun suggested(
        alarmId: String,
        fallbacks: Int,
        newestAgo: Duration,
    ) = ReRegisterSuggestion(alarmId, camera, fallbacks, newestFallbackAt = now - newestAgo)

    @Test
    fun `the table`() {
        fun two() = listOf(fallback(ago = 1.days), fallback(ago = 2.days))
        val cases =
            listOf(
                "2 fallbacks" to two(),
                "3 within 7 days" to listOf(fallback(ago = 1.days), fallback(ago = 3.days), fallback(ago = 7.days)),
                "one of 3 older than 7 days" to two() + fallback(ago = 7.days + 1.hours),
                "one of 3 at 7 days and 1 ms" to two() + fallback(ago = 7.days + 1.milliseconds),
                "a Test session among the 3" to two() + fallback(ago = 3.days, outcome = SessionOutcome.Test),
                "two alarms with 2 each" to
                    listOf(fallback("a", 1.days), fallback("a", 2.days), fallback("b", 1.days), fallback("b", 2.days)),
                "another replaced check" to two() + fallback(ago = 3.days, from = "Math"),
                "sessions without the fallback" to two() + fallback(ago = 3.days, used = false),
            )
        val results = cases.associate { (name, history) -> name to suggest(history) }

        assertEquals(
            mapOf(
                "2 fallbacks" to null,
                "3 within 7 days" to suggested("a", 3, newestAgo = 1.days),
                "one of 3 older than 7 days" to null,
                "one of 3 at 7 days and 1 ms" to null,
                "a Test session among the 3" to null,
                "two alarms with 2 each" to null,
                "another replaced check" to null,
                "sessions without the fallback" to null,
            ),
            results,
        )
    }

    @Test
    fun `re-registered after the fallbacks - none, until 3 new ones`() {
        val reRegistered = registeredA.copy(registeredAt = now - 12.hours)
        val old = listOf(fallback(ago = 1.days), fallback(ago = 2.days), fallback(ago = 3.days))

        assertNull(suggest(old, checks = listOf(reRegistered)))
        val fresh = listOf(fallback(ago = 3.hours), fallback(ago = 2.hours), fallback(ago = 1.hours))
        assertEquals(suggested("a", 3, newestAgo = 1.hours), suggest(old + fresh, checks = listOf(reRegistered)))
    }

    @Test
    fun `a fallback counts only when it rang after the registration, not at the same instant`() {
        val registered = listOf(registeredA.copy(registeredAt = now - 5.days))
        val atRegistration = listOf(fallback(ago = 5.days), fallback(ago = 2.days), fallback(ago = 1.days))
        val justAfter = listOf(fallback(ago = 5.days - 1.milliseconds), fallback(ago = 2.days), fallback(ago = 1.days))

        assertNull(suggest(atRegistration, checks = registered))
        assertEquals(suggested("a", 3, newestAgo = 1.days), suggest(justAfter, checks = registered))
    }

    @Test
    fun `a dismissal waits for 3 new fallbacks, an older dismissal than the registration changes nothing`() {
        val three = listOf(fallback(ago = 3.days), fallback(ago = 2.days), fallback(ago = 1.days))
        val key = CheckKey("a", camera.id)

        assertNull(suggest(three, dismissedAt = mapOf(key to now - 12.hours)))
        assertEquals(suggested("a", 3, newestAgo = 1.days), suggest(three, dismissedAt = mapOf(key to now - 25.days)))
        val afterDismissal = three + listOf(fallback(ago = 3.hours), fallback(ago = 2.hours), fallback(ago = 1.hours))
        assertEquals(suggested("a", 3, newestAgo = 1.hours), suggest(afterDismissal, dismissedAt = mapOf(key to now - 12.hours)))
    }

    @Test
    fun `a dismissal older than the registration counts from the registration`() {
        val registered = listOf(registeredA.copy(registeredAt = now - 4.days))
        val dismissed = mapOf(CheckKey("a", camera.id) to now - 6.days)
        val four = listOf(fallback(ago = 5.days), fallback(ago = 3.days), fallback(ago = 2.days), fallback(ago = 1.days))
        val oneAfterRegistration = listOf(fallback(ago = 5.days), fallback(ago = 4.days + 12.hours), fallback(ago = 1.days))

        assertEquals(suggested("a", 3, newestAgo = 1.days), suggest(four, checks = registered, dismissedAt = dismissed))
        assertNull(suggest(oneAfterRegistration, checks = registered, dismissedAt = dismissed))
    }

    @Test
    fun `only a camera check the alarm still has, of an alarm that still exists`() {
        val three = listOf(fallback(ago = 1.days), fallback(ago = 2.days), fallback(ago = 3.days))

        assertNull(suggest(three, checks = listOf(registeredB)), "check no longer configured")
        assertNull(suggest(three, alarms = listOf(alarmB)), "alarm deleted")
        assertNull(reRegisterSuggestion(three, listOf(alarmA), listOf(registeredA), now), "no camera type in production yet")
        assertNull(suggest(listOf(fallback(ago = 1.days), fallback(ago = 2.days), fallback(ago = -1.hours))), "not in the future")
    }

    @Test
    fun `with two suggestions the newest fallback wins, not the most fallbacks`() {
        val a = listOf(fallback("a", 6.days), fallback("a", 5.days), fallback("a", 4.days), fallback("a", 3.days + 12.hours))
        val b = listOf(fallback("b", 3.days), fallback("b", 2.days), fallback("b", 1.days))

        assertEquals(suggested("b", 3, newestAgo = 1.days), suggest(a + b))
    }

    @Test
    fun `the Home source combines its inputs and stores a dismissal at the newest fallback, whatever the clock`() =
        runTest {
            val rows = MutableStateFlow(listOf(fallback(ago = 1.days), fallback(ago = 2.days), fallback(ago = 3.days)))
            val dismissals =
                object : ReRegisterDismissals {
                    val stored = MutableStateFlow<Map<CheckKey, Instant>>(emptyMap())

                    override fun dismissed(): Flow<Map<CheckKey, Instant>> = stored

                    override suspend fun dismiss(
                        key: CheckKey,
                        at: Instant,
                    ): Outcome<Unit, DomainError> {
                        stored.value = stored.value + (key to at)
                        return Outcome.Success(Unit)
                    }
                }
            var time = now
            val suggestions =
                ReRegisterSuggestions(
                    history = { rows },
                    registrations = { flowOf(listOf(registeredA)) },
                    dismissals = dismissals,
                    clock =
                        object : Clock {
                            override fun now(): Instant = time
                        },
                    usesCamera = cameraStandIn,
                )

            val inputs = suggestions.inputs().first()
            assertEquals(ReRegisterInputs(rows.value, listOf(registeredA), emptyMap()), inputs)
            val suggestion = suggested("a", 3, newestAgo = 1.days)
            assertEquals(suggestion, suggestions.suggestion(inputs, listOf(alarmA)))
            assertNull(suggestions.suggestion(inputs, emptyList()), "the alarm Home lists is gone")
            time = now + 4.days + 1.hours
            assertNull(suggestions.suggestion(inputs, listOf(alarmA)), "the oldest left the window")

            // Dismissed while the clock is 30 days ahead: the mark is the newest fallback, not the wrong clock.
            time = now + 30.days
            assertEquals(Outcome.Success(Unit), suggestions.dismiss(suggestion))
            assertEquals(mapOf(CheckKey("a", camera.id) to now - 1.days), dismissals.stored.value)
            time = now
            assertNull(suggestions.suggestion(suggestions.inputs().first(), listOf(alarmA)), "dismissed")
            // After the correction, 3 new fallbacks bring it back: nothing was silenced for 30 days.
            rows.value = rows.value + listOf(fallback(ago = 3.hours), fallback(ago = 2.hours), fallback(ago = 1.hours))
            val back = suggestions.suggestion(suggestions.inputs().first(), listOf(alarmA))
            assertEquals(suggested("a", 3, newestAgo = 1.hours), back)
            assertEquals(emptyList(), CheckRegistrations.None.observe().first())
        }
}

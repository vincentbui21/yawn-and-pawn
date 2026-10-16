package com.yawnandpawn.app.core.telemetry

import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.session.SessionEnd
import com.yawnandpawn.app.core.session.ringSession
import com.yawnandpawn.app.core.session.testConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 5.6 (AD-15): only three events, each with one integer or enum field, and what a session sends. */
class TelemetryEventTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `there are exactly three events and every field is an Int or an enum, never free text`() {
        val events =
            TelemetryEvent
                .serializer()
                .descriptor
                .getElementDescriptor(1)
                .elementDescriptors
                .toList()

        assertEquals(
            listOf(
                "SessionOutcome",
                "SnoozeCount",
                "CheckTypeUsed",
            ).map { "com.yawnandpawn.app.core.telemetry.TelemetryEvent.$it" }.toSet(),
            events.map { it.serialName }.toSet(),
        )
        events.forEach { event ->
            assertEquals(1, event.elementsCount, event.serialName)
            event.elementDescriptors.forEach { field ->
                assertTrue(
                    field.kind == PrimitiveKind.INT || field.kind == SerialKind.ENUM,
                    "${event.serialName}: ${field.serialName} ${field.kind}",
                )
            }
        }
    }

    @Test
    fun `the checks a user can pick are named, the Placeholder is not`() {
        assertEquals(TelemetryCheckType.Math, TelemetryCheckType.of(CheckType.Math))
        assertEquals(TelemetryCheckType.WordUnscramble, TelemetryCheckType.of(CheckType.WordUnscramble))
        assertEquals(TelemetryCheckType.MemorySequence, TelemetryCheckType.of(CheckType.MemorySequence(numbered = true)))
        assertEquals(TelemetryCheckType.QrBarcode, TelemetryCheckType.of(CheckType.QrBarcode))
        assertNull(TelemetryCheckType.of(CheckType.Placeholder))
        assertEquals(CheckType.all.size - 1, CheckType.all.mapNotNull { TelemetryCheckType.of(it) }.size)
    }

    private fun entry(type: CheckType) = CheckEntry(type, Difficulty.Easy, count = 1)

    @Test
    fun `a missed session sends Missed, its snooze count and each distinct named check type, in plan order`() {
        val plan =
            CheckPlan(
                CheckMode.All,
                listOf(entry(CheckType.MemorySequence()), entry(CheckType.Math), entry(CheckType.MemorySequence(numbered = true))),
            )
        val session = ringSession(testConfig(checkPlan = plan)).copy(snoozesGranted = 3)

        assertEquals(
            listOf(
                TelemetryEvent.SessionOutcome(SessionOutcome.Missed),
                TelemetryEvent.SnoozeCount(3),
                TelemetryEvent.CheckTypeUsed(TelemetryCheckType.MemorySequence),
                TelemetryEvent.CheckTypeUsed(TelemetryCheckType.Math),
            ),
            sessionTelemetry(session, SessionEnd.Missed),
        )
    }

    @Test
    fun `a completed session without a snooze is on time`() {
        val events = sessionTelemetry(ringSession(), SessionEnd.Completed)

        assertEquals(listOf(TelemetryEvent.SessionOutcome(SessionOutcome.OnTime), TelemetryEvent.SnoozeCount(0)), events)
    }

    @Test
    fun `a test session sends nothing, whatever its ending`() {
        val test = ringSession(testConfig(testMode = true))

        assertEquals(emptyList(), sessionTelemetry(test, SessionEnd.Completed))
        assertEquals(emptyList(), sessionTelemetry(test, SessionEnd.Missed))
    }
}

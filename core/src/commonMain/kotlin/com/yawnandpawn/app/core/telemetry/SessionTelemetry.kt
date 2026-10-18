package com.yawnandpawn.app.core.telemetry

import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEnd
import com.yawnandpawn.app.core.session.SessionRecorder

/**
 * The usage stats of a session that ended as [end] (AD-15): its outcome, its paid snooze count and one event per
 * distinct check type of its plan (types usage stats do not name are left out). A test session sends nothing.
 */
fun sessionTelemetry(
    session: SessionData,
    end: SessionEnd,
): List<TelemetryEvent> {
    if (session.config.testMode) return emptyList()
    val checks =
        session.checkRun.plan.entries
            .mapNotNull { TelemetryCheckType.of(it.type) }
            .distinct()
            .map { TelemetryEvent.CheckTypeUsed(it) }
    return listOf(
        TelemetryEvent.SessionOutcome(SessionRecorder.outcomeOf(session, end)),
        TelemetryEvent.SnoozeCount(session.snoozesGranted),
    ) + checks
}

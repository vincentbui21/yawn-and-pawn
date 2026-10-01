package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger

/** Keeps every logged event in order, for tests to check what was logged and how often. */
class FakeLogger : Logger {
    private val logged = mutableListOf<LogEvent>()

    /** Every event logged so far, oldest first. */
    val events: List<LogEvent>
        get() = logged.toList()

    override fun log(event: LogEvent) {
        logged += event
    }
}

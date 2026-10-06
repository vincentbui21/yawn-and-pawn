package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.crash.CrashReporter

/** [CrashReporter] that keeps every reported exception in order, in [reported]. */
class FakeCrashReporter : CrashReporter {
    private val received = mutableListOf<Throwable>()

    val reported: List<Throwable>
        get() = received.toList()

    override fun report(throwable: Throwable) {
        received += throwable
    }
}

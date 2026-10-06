package com.yawnandpawn.app.testing

import kotlin.test.Test
import kotlin.test.assertEquals

class FakeCrashReporterTest {
    @Test
    fun `the fake crash reporter keeps every report in order`() {
        val reporter = FakeCrashReporter()
        val first = IllegalStateException("one")
        val second = RuntimeException("two")

        reporter.report(first)
        reporter.report(second)

        assertEquals(listOf<Throwable>(first, second), reporter.reported)
    }
}

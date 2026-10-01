package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.session.CheckPlan
import com.yawnandpawn.app.core.session.CheckStep
import kotlin.test.Test
import kotlin.test.assertEquals

class WakeFakesTest {
    @Test
    fun `the fake crash reporter keeps every report in order`() {
        val reporter = FakeCrashReporter()
        val first = IllegalStateException("one")
        val second = RuntimeException("two")

        reporter.report(first)
        reporter.report(second)

        assertEquals(listOf<Throwable>(first, second), reporter.reported)
    }

    @Test
    fun `the fake seed source gives consecutive seeds per step and remembers the plans`() {
        val seeds = FakeSeedSource(first = 10)
        val plan = CheckPlan(listOf(CheckStep.Placeholder, CheckStep.Placeholder))

        assertEquals(listOf(10L, 11L), seeds.seedsFor(plan))
        assertEquals(listOf(1L), FakeSeedSource().seedsFor(CheckPlan.placeholder()))
        assertEquals(listOf(plan), seeds.asked)
    }
}

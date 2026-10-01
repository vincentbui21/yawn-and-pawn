package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.crash.CrashReporter
import com.yawnandpawn.app.core.session.CheckPlan
import com.yawnandpawn.app.core.session.SeedSource

/** [CrashReporter] that keeps every reported exception in order, in [reported]. */
class FakeCrashReporter : CrashReporter {
    private val received = mutableListOf<Throwable>()

    val reported: List<Throwable>
        get() = received.toList()

    override fun report(throwable: Throwable) {
        received += throwable
    }
}

/** [SeedSource] with fixed seeds: step i of a plan gets [first] + i. Every plan it was asked for is kept in [asked]. */
class FakeSeedSource(
    private val first: Long = 1L,
) : SeedSource {
    private val plans = mutableListOf<CheckPlan>()

    val asked: List<CheckPlan>
        get() = plans.toList()

    override fun seedsFor(plan: CheckPlan): List<Long> {
        plans += plan
        return List(plan.steps.size) { first + it }
    }
}

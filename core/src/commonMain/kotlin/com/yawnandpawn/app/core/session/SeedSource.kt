package com.yawnandpawn.app.core.session

import kotlin.random.Random

/**
 * Port for the check seeds (AD-9) a new `CheckRun` starts with: the reducer is pure, so whoever builds `AlarmFired`
 * (the wake service) takes them from here. `FakeSeedSource` in tests.
 */
fun interface SeedSource {
    /** One seed per step of [plan]. */
    fun seedsFor(plan: CheckPlan): List<Long>
}

/** Seeds from [random] (by default the platform's secure-seeded `Random.Default`). */
class RandomSeedSource(
    private val random: Random = Random.Default,
) : SeedSource {
    override fun seedsFor(plan: CheckPlan): List<Long> = List(plan.steps.size) { random.nextLong() }
}

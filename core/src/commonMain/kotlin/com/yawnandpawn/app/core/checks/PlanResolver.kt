package com.yawnandpawn.app.core.checks

/** Resolves the plan of one ring (FR-PWK-2, AD-9). Pure. */
object PlanResolver {
    /**
     * The plan the ring runs: [CheckMode.All] keeps every entry in order; [CheckMode.Random] keeps the one entry that
     * [pickSeed] picks, so another ring (another seed) can pick another type. The result is always in [CheckMode.All]
     * mode. An empty plan stays empty.
     */
    fun resolve(
        plan: CheckPlan,
        pickSeed: Long,
    ): CheckPlan =
        when {
            plan.mode == CheckMode.All -> plan
            plan.entries.size <= 1 -> plan.copy(mode = CheckMode.All)
            else -> CheckPlan(CheckMode.All, listOf(plan.entries[SeededRandom(pickSeed).nextInt(plan.entries.indices)]))
        }
}

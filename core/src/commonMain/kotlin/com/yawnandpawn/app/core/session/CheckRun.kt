package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.PlanResolver
import com.yawnandpawn.app.core.checks.SeedDeriver
import kotlinx.serialization.Serializable

/** Where the user is in a ring's resolved plan: item [item] (0-based) of entry [entry]. */
@Serializable
data class StepPointer(
    val entry: Int = 0,
    val item: Int = 0,
)

/**
 * Progress through the session's check (AD-2, AD-9). [plan] is the ring's resolved plan ([PlanResolver], always in All
 * mode) and [seeds] hold one seed per entry, from [SeedDeriver] only, so each puzzle is deterministic.
 *
 * @property step the current item of the current entry; past the last entry once the check is passed.
 * @property failedAttempts wrong answers and failed matches on the current entry; reset when the entry advances.
 * @property fallbackUsed the fallback check (FR-PWK-11) has replaced the plan; it is offered once. Its seeds use the
 * fallback keys of [SeedDeriver].
 */
@Serializable
data class CheckRun(
    val plan: CheckPlan,
    val seeds: List<Long>,
    val step: StepPointer = StepPointer(),
    val failedAttempts: Int = 0,
    val fallbackUsed: Boolean = false,
) {
    /** The entry the user is on, or null once every entry is passed. */
    val currentEntry: CheckEntry?
        get() = plan.entries.getOrNull(step.entry)

    /** Progress dropped after a granted snooze: back to the first item with no failed attempts; the next ring re-resolves. */
    fun restart(): CheckRun = copy(step = StepPointer(), failedAttempts = 0)

    /**
     * The same run on [newPlan] (Direct Boot substitutions on a restore, Story 2.3): when the current entry changed,
     * its progress starts again at the first item.
     */
    internal fun withPlan(newPlan: CheckPlan): CheckRun =
        when {
            newPlan == plan -> this
            newPlan.entries.getOrNull(step.entry) == currentEntry -> copy(plan = newPlan)
            else -> copy(plan = newPlan, step = step.copy(item = 0))
        }

    /** The [SeedDeriver] key of entry [entry] of this run: the fallback plan uses its own keys. */
    internal fun seedKey(entry: Int): Int = if (fallbackUsed) SeedDeriver.FALLBACK_BASE + entry else entry

    companion object {
        /**
         * The run of ring [ringIndex] of session [sessionId]: [plan] resolved for the ring, then [substitute]d (the Direct
         * Boot check while locked), with each entry's first seed. [fallback] runs use the fallback keys.
         */
        internal fun forRing(
            plan: CheckPlan,
            sessionId: String,
            ringIndex: Int,
            fallback: Boolean = false,
            substitute: (CheckPlan) -> CheckPlan = { it },
        ): CheckRun {
            val base = if (fallback) SeedDeriver.FALLBACK_BASE else 0
            val resolved = substitute(PlanResolver.resolve(plan, SeedDeriver.seed(sessionId, ringIndex, base + SeedDeriver.PICK, 0)))
            val seeds = resolved.entries.indices.map { SeedDeriver.seed(sessionId, ringIndex, base + it, 0) }
            return CheckRun(resolved, seeds, fallbackUsed = fallback)
        }
    }
}

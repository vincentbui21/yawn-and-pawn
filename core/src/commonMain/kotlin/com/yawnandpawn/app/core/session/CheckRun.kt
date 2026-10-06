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
 * @property failedAttempts wrong answers and failed matches on the current entry; reset when the entry advances. It keys
 * the seed of a restarted puzzle.
 * @property fallbackUsed the fallback check (FR-PWK-11) has replaced the plan; it is offered once. Its seeds use the
 * fallback flag of [SeedDeriver].
 * @property fallbackSource the fallback plan as the `FallbackPolicy` gave it, before it was resolved and substituted:
 * each later ring resolves it again (a Random fallback picks again, an unlocked ring drops the Direct Boot
 * substitutions). Null before the fallback, and in a run stored before it was kept (the next ring then reuses [plan]).
 * @property totalFailedAttempts every failed attempt of the session, on every entry and ring, never reset: what the
 * `FallbackPolicy` (Story 3.9) and history read, unlike [failedAttempts].
 */
@Serializable
data class CheckRun(
    val plan: CheckPlan,
    val seeds: List<Long>,
    val step: StepPointer = StepPointer(),
    val failedAttempts: Int = 0,
    val fallbackUsed: Boolean = false,
    val fallbackSource: CheckPlan? = null,
    val totalFailedAttempts: Int = 0,
) {
    /** The entry the user is on, or null once every entry is passed. */
    val currentEntry: CheckEntry?
        get() = plan.entries.getOrNull(step.entry)

    /** The plan the next ring resolves its run from: the fallback once used, else the session's [configPlan]. */
    internal fun nextRingPlan(configPlan: CheckPlan): CheckPlan = if (fallbackUsed) fallbackSource ?: plan else configPlan

    /**
     * Progress dropped after a granted snooze: back to the first item with no failed attempts on the entry; the next ring
     * re-resolves. The session's [totalFailedAttempts] stay.
     */
    fun restart(): CheckRun = copy(step = StepPointer(), failedAttempts = 0)

    /** The [SeedDeriver] seed of entry [entry] of this run for [attempt]: the fallback plan uses the fallback flag. */
    internal fun seedOf(
        sessionId: String,
        ringIndex: Int,
        entry: Int,
        attempt: Int,
    ): Long = SeedDeriver.seed(sessionId, ringIndex, entry, attempt, fallback = fallbackUsed)

    /**
     * This run with a seed for every entry: a damaged row with fewer seeds than entries gets the missing ones derived
     * again from the session coordinates (each entry's first seed), so its answers can still be checked.
     */
    internal fun withMissingSeeds(
        sessionId: String,
        ringIndex: Int,
    ): CheckRun =
        if (seeds.size >= plan.entries.size) {
            this
        } else {
            copy(seeds = plan.entries.indices.map { seeds.getOrNull(it) ?: seedOf(sessionId, ringIndex, it, attempt = 0) })
        }

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

    companion object {
        /**
         * The run of ring [ringIndex] of session [sessionId]: [plan] resolved for the ring, then [substitute]d (the Direct
         * Boot check while locked), with each entry's first seed. [fallback] runs use the fallback seeds and keep [plan]
         * as their [fallbackSource].
         */
        internal fun forRing(
            plan: CheckPlan,
            sessionId: String,
            ringIndex: Int,
            fallback: Boolean = false,
            substitute: (CheckPlan) -> CheckPlan = { it },
        ): CheckRun {
            val pickSeed = SeedDeriver.seed(sessionId, ringIndex, SeedDeriver.PICK, 0, fallback)
            val resolved = substitute(PlanResolver.resolve(plan, pickSeed))
            val seeds = resolved.entries.indices.map { SeedDeriver.seed(sessionId, ringIndex, it, 0, fallback) }
            return CheckRun(resolved, seeds, fallbackUsed = fallback, fallbackSource = plan.takeIf { fallback })
        }
    }
}

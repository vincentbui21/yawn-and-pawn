package com.yawnandpawn.app.core.checks

import kotlinx.serialization.Serializable

/** How a ring uses an alarm's checks (FR-PWK-2): one of them per ring, or all of them in order. */
@Serializable
enum class CheckMode {
    /** Each ring picks one entry by its seed, so a re-ring can pick another one. */
    Random,

    /** Every entry, in the user's order. */
    All,
}

/** One check of an alarm's plan: [type] at [difficulty], with [count] items (problems, words, rounds). */
@Serializable
data class CheckEntry(
    val type: CheckType,
    val difficulty: Difficulty,
    val count: Int,
)

/**
 * An alarm's checks (FR-PWK-2): [entries] in the user's order, used as [mode] says. A ring runs the plan that
 * [PlanResolver] resolves from it, which is always in [CheckMode.All] mode.
 */
@Serializable
data class CheckPlan(
    val mode: CheckMode,
    val entries: List<CheckEntry>,
) {
    companion object {
        /** The one entry of [placeholder]. */
        val PLACEHOLDER_ENTRY: CheckEntry = CheckEntry(CheckType.Placeholder, Difficulty.Medium, count = 1)

        /** The Epic 1 plan that production alarms ring with until Story 3.2: one [CheckType.Placeholder] entry. */
        fun placeholder(): CheckPlan = CheckPlan(CheckMode.All, listOf(PLACEHOLDER_ENTRY))
    }
}

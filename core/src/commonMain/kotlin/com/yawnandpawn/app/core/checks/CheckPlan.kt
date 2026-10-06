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
        /** Math · Medium · 3: the default entry (owner-approved default 2026-09-26) and the Direct Boot check (FR-ALM-11). */
        val DEFAULT_ENTRY: CheckEntry = CheckEntry(CheckType.Math, Difficulty.Medium, count = CheckType.Math.defaultCount)

        /** The one entry of [placeholder]. */
        val PLACEHOLDER_ENTRY: CheckEntry = CheckEntry(CheckType.Placeholder, Difficulty.Medium, count = 1)

        /** The plan of every alarm and of the test alarm until per-alarm checks (Story 3.5): Random, one Math · Medium · 3. */
        fun default(): CheckPlan = CheckPlan(CheckMode.Random, listOf(DEFAULT_ENTRY))

        /**
         * The Epic 1 plan: one [CheckType.Placeholder] entry, which "I'm up" alone passes. Since Story 3.2 no production
         * code makes it; it remains for sessions stored by Epics 1–2 and for tests that are not about the check.
         */
        fun placeholder(): CheckPlan = CheckPlan(CheckMode.All, listOf(PLACEHOLDER_ENTRY))
    }
}

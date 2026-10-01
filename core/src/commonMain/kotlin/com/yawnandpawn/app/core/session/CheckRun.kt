package com.yawnandpawn.app.core.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One step of a wake-up check (AD-9). Epic 3 adds the real check types; Epic 1 has only [Placeholder]. */
@Serializable
sealed interface CheckStep {
    /** The Epic 1 stand-in: "I'm up" alone completes it. */
    @Serializable
    @SerialName("Placeholder")
    data object Placeholder : CheckStep
}

/**
 * The stable name of this step's check type, as session history stores it (AD-18). Never derived from class names,
 * so renaming a class cannot change stored history; a name never contains a comma.
 */
val CheckStep.typeName: String
    get() =
        when (this) {
            CheckStep.Placeholder -> "Placeholder"
        }

/** The steps the user must pass to end the session, in order. */
@Serializable
data class CheckPlan(
    val steps: List<CheckStep>,
) {
    companion object {
        /** The Epic 1 plan: one [CheckStep.Placeholder] step. */
        fun placeholder(): CheckPlan = CheckPlan(listOf(CheckStep.Placeholder))
    }
}

/**
 * Progress through the session's check (AD-2). [seeds] make each step's puzzle deterministic (AD-9); they are chosen
 * outside the reducer and arrive in the event that starts the run.
 *
 * @property step index of the current step in [plan].
 * @property failedAttempts wrong answers and failed matches so far.
 * @property fallbackUsed the fallback check (FR-PWK-11) has replaced the plan; it is offered once.
 */
@Serializable
data class CheckRun(
    val plan: CheckPlan,
    val seeds: List<Long>,
    val step: Int = 0,
    val failedAttempts: Int = 0,
    val fallbackUsed: Boolean = false,
) {
    /** The step the user is on, or null once every step is passed. */
    val currentStep: CheckStep?
        get() = plan.steps.getOrNull(step)

    /** Progress dropped after a granted snooze: back to the first step with [newSeeds]; the plan stays. */
    fun restart(newSeeds: List<Long>): CheckRun = copy(seeds = newSeeds, step = 0, failedAttempts = 0)
}

/** What the user submitted for the current check step (AD-9). Epic 3 adds one answer type per check. */
sealed interface CheckAnswer {
    /** The answer to a [CheckStep.Placeholder] step. */
    data object Placeholder : CheckAnswer

    /** The image matcher matched the photo (House Hunt); it re-enters as `ImageMatchCompleted(matched = true)`. */
    data object ImageMatched : CheckAnswer
}

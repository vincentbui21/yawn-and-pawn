package com.yawnandpawn.app.core.checks

/**
 * What a [CheckType] decided about one answer (AD-9). Named apart from the session's `StepResult`, which keeps the AD-2
 * row mapping; `PluginCheckValidator` maps one onto the other.
 */
sealed interface CheckResult {
    /** Right, and more items of the puzzle remain. */
    data object ItemCorrect : CheckResult

    /** Right, and the puzzle is done. */
    data object Correct : CheckResult

    /** Wrong; the user tries the same item again. */
    data object Wrong : CheckResult

    /**
     * Wrong, and the puzzle starts over as a new one. The plugin does not know the session, so the reducer takes the new
     * seed from `SeedDeriver` (seeds come only from there) and stores it for the entry.
     */
    data object WrongRestart : CheckResult
}

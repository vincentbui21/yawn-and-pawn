package com.yawnandpawn.app.core.config

import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType

/** What a settings change does to the user's commitment (PRD §6.2). */
enum class ChangeKind {
    /** Makes waking up easier or snoozing cheaper: waits until after the next alarm when made inside the lock window. */
    Weakening,

    /** Makes it harder or dearer: always applies at once. */
    Strengthening,

    /** The same value: applies at once (and cancels a pending change of the field). */
    NoChange,
}

/**
 * Classifies a change of one [LockedField] from the current effective value [old] to [new] (Story 4.4 rules):
 * - a lower base fee, more max snoozes and a longer grace window are [ChangeKind.Weakening]; the opposite directions are
 *   [ChangeKind.Strengthening]; an equal value is [ChangeKind.NoChange];
 * - checks: see [classifyChecks].
 *
 * [old] and [new] must be values of the same field.
 */
fun classify(
    old: SettingValue,
    new: SettingValue,
): ChangeKind {
    require(old.field == new.field) { "a ${old.field} value compared with a ${new.field} value" }
    return when (old) {
        is SettingValue.BaseFeeTier -> higherIsStronger(old.tier, (new as SettingValue.BaseFeeTier).tier)
        is SettingValue.MaxSnoozes -> lowerIsStronger(old.count, (new as SettingValue.MaxSnoozes).count)
        is SettingValue.GraceSeconds -> lowerIsStronger(old.seconds, (new as SettingValue.GraceSeconds).seconds)
        is SettingValue.Checks -> classifyChecks(old.plan, (new as SettingValue.Checks).plan)
    }
}

/**
 * A check-plan change (owner-approved default 2026-09-26):
 * - [ChangeKind.NoChange] when the mode is the same and the entries are the same types at the same difficulty and count,
 *   in any order; a registered code (Story 3.10) and the order do not make a plan easier, so a new sticker or a reorder
 *   is never held back;
 * - [ChangeKind.Strengthening] when [new] uses mode All, contains every type of [old], and each type's difficulty (for a
 *   type that has one) and count are at least the old ones; or (review fix 12) when both are Random with the same types,
 *   each at least as hard (a ring picks one of the same types, never an easier one);
 * - anything else (fewer types, a type added to or swapped in a Random plan, All to Random, a lower difficulty or
 *   count) is [ChangeKind.Weakening].
 *
 * A plan with at most one check runs it on every ring whatever its mode, so it counts as All (review fix 12: Math Easy
 * to Math Hard in Random mode is harder).
 */
fun classifyChecks(
    old: CheckPlan,
    new: CheckPlan,
): ChangeKind {
    val oldMode = old.effectiveMode()
    val newMode = new.effectiveMode()
    val everyOldAtLeastAsHard = old.counted().all { before -> new.counted().any { it.atLeast(before) } }
    val sameTypes = old.counted().map { it.type.id }.toSet() == new.counted().map { it.type.id }.toSet()
    return when {
        oldMode == newMode && old.strength() == new.strength() -> ChangeKind.NoChange
        newMode == CheckMode.All && everyOldAtLeastAsHard -> ChangeKind.Strengthening
        oldMode == CheckMode.Random && newMode == CheckMode.Random && sameTypes && everyOldAtLeastAsHard -> ChangeKind.Strengthening
        else -> ChangeKind.Weakening
    }
}

/** [CheckPlan.mode], except that a plan with at most one counted check runs it on every ring: All. */
private fun CheckPlan.effectiveMode(): CheckMode = if (counted().size <= 1) CheckMode.All else mode

private fun higherIsStronger(
    old: Int,
    new: Int,
): ChangeKind =
    when {
        new > old -> ChangeKind.Strengthening
        new < old -> ChangeKind.Weakening
        else -> ChangeKind.NoChange
    }

private fun lowerIsStronger(
    old: Int,
    new: Int,
): ChangeKind = higherIsStronger(new, old)

/** What makes the plan hard: each entry's type, difficulty (when the type has one) and count, in no order. */
private fun CheckPlan.strength(): Map<String, Pair<Int, Int>> = counted().associate { it.type.id to (it.level() to it.count) }

/** The entries that make a plan hard: the Epic 1 placeholder (passed by "I'm up" alone) adds nothing, so dropping it never weakens. */
private fun CheckPlan.counted(): List<CheckEntry> = entries.filter { it.type != CheckType.Placeholder }

private fun CheckEntry.level(): Int = if (type.hasDifficulty) difficulty.ordinal else 0

/** This entry is the same type as [other] and at least as hard. */
private fun CheckEntry.atLeast(other: CheckEntry): Boolean = type.id == other.type.id && level() >= other.level() && count >= other.count

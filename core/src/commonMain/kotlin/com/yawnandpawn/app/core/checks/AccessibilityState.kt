package com.yawnandpawn.app.core.checks

/**
 * Whether a screen reader (TalkBack) is on right now (NFR-9). The checks read it when a ring's plan is frozen, to pick
 * the accessible Memory Sequence variant (Story 3.8), and the editor reads it for its notes and "Try it". The Android
 * adapter asks the accessibility service; tests use `FakeAccessibilityState`.
 */
fun interface AccessibilityState {
    fun isScreenReaderOn(): Boolean
}

/** [entries] with every Memory Sequence entry in its numbered variant when [accessible], else unchanged. */
fun accessibleEntries(
    entries: List<CheckEntry>,
    accessible: Boolean,
): List<CheckEntry> =
    if (!accessible) {
        entries
    } else {
        entries.map { entry ->
            if (entry.type is CheckType.MemorySequence) entry.copy(type = CheckType.MemorySequence(numbered = true)) else entry
        }
    }

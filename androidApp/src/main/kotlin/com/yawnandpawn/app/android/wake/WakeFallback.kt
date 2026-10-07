package com.yawnandpawn.app.android.wake

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yawnandpawn.app.core.checks.AccessibilityState
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.accessibleEntries
import com.yawnandpawn.app.core.session.FallbackPolicy
import com.yawnandpawn.app.core.session.FallbackReason
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.offers

/**
 * The fallback part of [WakeCheck] (Story 3.9): whether the fallback is offered (the [policy] for the [reason] now: the
 * camera unavailable or failed attempts, Story 3.10), whether the
 * Fallback check picker is open, and the type a picked card stands for.
 */
internal class WakeFallback(
    private val policy: FallbackPolicy,
    private val accessibility: AccessibilityState,
    private val reason: (SessionState) -> FallbackReason,
) {
    /** The Fallback check picker was opened from the link and not closed. */
    var pickerOpen by mutableStateOf(false)

    /** Whether the fallback is offered for [state] now: in Grace or Loud, not used yet, and the [policy] allows it. */
    fun offered(state: SessionState): Boolean {
        val session = (state as? SessionState.Grace)?.session ?: (state as? SessionState.Loud)?.session
        return session != null && !session.checkRun.fallbackUsed && policy.offers(session, reason(state))
    }

    /** [type] as a ring's frozen plan would hold it now: Memory Sequence numbered while TalkBack is on ([accessibility]). */
    fun asFrozen(type: CheckType): CheckType =
        accessibleEntries(listOf(CheckEntry(type, Difficulty.Hard, type.defaultCount)), accessibility.isScreenReaderOn()).single().type
}

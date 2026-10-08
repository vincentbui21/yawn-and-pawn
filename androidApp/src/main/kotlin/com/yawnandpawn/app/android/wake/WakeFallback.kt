package com.yawnandpawn.app.android.wake

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yawnandpawn.app.core.checks.CheckType
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
    private val reason: (SessionState) -> FallbackReason,
    open: MutableState<Boolean> = mutableStateOf(false),
) {
    /** The Fallback check picker was opened from the link and not closed; kept across a recreated screen (3.11 review). */
    var pickerOpen by open

    /** Whether the fallback is offered for [state] now: in Grace or Loud, not used yet, and the [policy] allows it. */
    fun offered(state: SessionState): Boolean {
        val session = (state as? SessionState.Grace)?.session ?: (state as? SessionState.Loud)?.session
        return session != null && !session.checkRun.fallbackUsed && policy.offers(session, reason(state))
    }

    /**
     * [type] as the fallback runs it: Memory Sequence always in its numbered, announced variant (epic Story 3.9: "in its
     * numbered accessible variant"; Story 3.12, default taken: not only while TalkBack is on), every other type as it is.
     */
    fun asFallback(type: CheckType): CheckType = if (type is CheckType.MemorySequence) CheckType.MemorySequence(numbered = true) else type
}

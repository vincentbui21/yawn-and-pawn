package com.yawnandpawn.app.android

import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.yawnandpawn.app.core.checks.AccessibilityState

/**
 * [AccessibilityState] from the system: a screen reader is on when accessibility is enabled with touch exploration (how
 * TalkBack runs). Read at each alarm fire (and by the editor), never cached, so turning TalkBack on applies to the next
 * session; a session keeps the plan frozen at its fire, snooze re-rings included.
 */
class AndroidAccessibilityState(
    context: Context,
) : AccessibilityState {
    private val manager = context.getSystemService(AccessibilityManager::class.java)

    override fun isScreenReaderOn(): Boolean = manager?.let { it.isEnabled && it.isTouchExplorationEnabled } == true
}

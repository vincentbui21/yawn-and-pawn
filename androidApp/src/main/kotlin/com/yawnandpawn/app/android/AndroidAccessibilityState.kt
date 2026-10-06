package com.yawnandpawn.app.android

import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.yawnandpawn.app.core.checks.AccessibilityState

/**
 * [AccessibilityState] from the system: a screen reader is on when accessibility is enabled with touch exploration (how
 * TalkBack runs). Read when needed, never cached, so turning TalkBack on applies to the next ring.
 */
class AndroidAccessibilityState(
    context: Context,
) : AccessibilityState {
    private val manager = context.getSystemService(AccessibilityManager::class.java)

    override fun isScreenReaderOn(): Boolean = manager?.let { it.isEnabled && it.isTouchExplorationEnabled } == true
}

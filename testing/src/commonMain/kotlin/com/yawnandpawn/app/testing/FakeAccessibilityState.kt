package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.checks.AccessibilityState

/** An [AccessibilityState] whose screen reader a test turns on or off with [screenReaderOn]. */
class FakeAccessibilityState(
    var screenReaderOn: Boolean = false,
) : AccessibilityState {
    override fun isScreenReaderOn(): Boolean = screenReaderOn
}

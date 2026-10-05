package com.yawnandpawn.app.ui.nav

/**
 * Opens the wake screen on the current session ("Back to alarm" on the session lock panel, Story 2.6). The wake screen
 * is its own activity outside this navigation graph, so `:androidApp` binds the implementation.
 */
fun interface WakeScreenOpener {
    fun open()
}

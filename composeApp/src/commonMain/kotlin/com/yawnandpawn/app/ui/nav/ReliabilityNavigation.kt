package com.yawnandpawn.app.ui.nav

import androidx.navigation3.runtime.NavKey

// The reliability screens' back stack rules, beside the shell's (TabNavigation.kt), which keep the same shape.

/**
 * Pushes the maker guidance (Story 5.5) over the tab on top, so Back returns to it. Ignored while a pushed screen (or
 * the session lock) is on top, so a double tap never stacks two. Story 5.4 moves it over the checklist screen.
 */
fun MutableList<NavKey>.openManufacturerGuidance() {
    if ((lastOrNull() as? Route)?.tab == null) return
    add(Route.ManufacturerGuidance)
}

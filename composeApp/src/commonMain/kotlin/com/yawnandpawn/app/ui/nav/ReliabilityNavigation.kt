package com.yawnandpawn.app.ui.nav

import androidx.navigation3.runtime.NavKey

// The reliability screens' back stack rules, beside the shell's (TabNavigation.kt), which keep the same shape.

/**
 * Pushes the reliability checklist (Story 5.4) over [from], the screen whose row or banner was tapped: Settings or
 * Home (the Alarms tab). Back returns there. Ignored unless [from] is on top, so a double tap never stacks two, a late
 * tap from a screen that is no longer in front does nothing, and nothing pushes it over the session lock.
 */
fun MutableList<NavKey>.openReliability(from: Route) {
    if (from != Route.Settings && from != Route.Alarms) return
    if (lastOrNull() != from) return
    add(Route.Reliability)
}

/**
 * Pushes the maker guidance (Story 5.5) over the reliability checklist, its only caller (the Manufacturer row's "Fix",
 * Story 5.4), so Back returns to it. Ignored unless the checklist is on top, so a double tap never stacks two.
 */
fun MutableList<NavKey>.openManufacturerGuidance() {
    if (lastOrNull() != Route.Reliability) return
    add(Route.ManufacturerGuidance)
}

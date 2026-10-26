package com.yawnandpawn.app.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.datetime.LocalDate

// The Progress tab's back stack rules, beside the shell's (TabNavigation.kt), which keep the same shape.

/**
 * Pushes Day detail of [date] over Progress (Story 6.7), from a ring or calendar chip or a second tap on the selected
 * day; Back returns to Progress. Ignored unless Progress is on top, so a double tap or a late chip tap never stacks two,
 * and nothing pushes it over the session lock.
 */
fun MutableList<NavKey>.openDayDetail(date: LocalDate) {
    if (lastOrNull() != Route.Progress) return
    add(Route.DayDetail(date))
}

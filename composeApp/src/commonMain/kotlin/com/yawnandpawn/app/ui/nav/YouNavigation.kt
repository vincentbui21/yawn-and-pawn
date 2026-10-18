package com.yawnandpawn.app.ui.nav

import androidx.navigation3.runtime.NavKey

// Back stack rules of the You tab's pushed screens (Stories 4.16, 4.17 and 5.8), next to the shell's rules in
// TabNavigation.kt: Purchase history or Payments over the tab, Purchase history over Payments, and "Problem with a
// charge?" over either of those. A repeated call (a double tap) changes nothing, and nothing is pushed over the session
// lock.

/**
 * Pushes Purchase history over the tab on top (the You tab's Money card) or over Payments (its link, Story 5.8), so
 * Back returns to that screen. Ignored while another pushed screen is on top, so a double tap never stacks two.
 */
fun MutableList<NavKey>.openPurchaseHistory() {
    if (topTab() == null && lastOrNull() != Route.Payments) return
    add(Route.PurchaseHistory)
}

/**
 * Pushes "How payments & refunds work" over the tab on top (the You tab's Money card, Story 5.8), so Back returns to
 * that tab. Ignored while a pushed screen is on top, so a double tap never stacks two.
 */
fun MutableList<NavKey>.openPayments() {
    if (topTab() == null) return
    add(Route.Payments)
}

/**
 * Pushes "Problem with a charge?" over Purchase history (Story 4.17) or Payments (Story 5.8), so Back returns to it.
 * Ignored unless one of them is on top, so a double tap never stacks two and nothing pushes it over the session lock.
 */
fun MutableList<NavKey>.openProblemWithCharge() {
    val top = lastOrNull()
    if (top != Route.PurchaseHistory && top != Route.Payments) return
    add(Route.ProblemWithCharge)
}

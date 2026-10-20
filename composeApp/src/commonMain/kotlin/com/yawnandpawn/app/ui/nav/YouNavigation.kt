package com.yawnandpawn.app.ui.nav

import androidx.navigation3.runtime.NavKey

// Back stack rules of the You tab's pushed screens (Stories 4.16, 4.17 and 5.8), next to the shell's rules in
// TabNavigation.kt: Purchase history or Payments over the tab, Purchase history over Payments, and "Problem with a
// charge?" over either of those. Each push names the screen it comes `from` and happens only while that screen is on
// top, so a repeated call (a double tap) or a second row tapped during the 250 ms slide changes nothing, and nothing is
// pushed over the session lock.

/**
 * Pushes Purchase history over [from], the tab on top (the You tab's Money card) or Payments (its link, Story 5.8), so
 * Back returns to that screen. Ignored unless [from] is on top, so a double tap never stacks two.
 */
fun MutableList<NavKey>.openPurchaseHistory(from: NavKey) {
    if (lastOrNull() != from || (topTab() == null && from != Route.Payments)) return
    add(Route.PurchaseHistory)
}

/**
 * Pushes "How payments & refunds work" over [from], the tab on top (the You tab's Money card, Story 5.8), so Back
 * returns to that tab. Ignored unless [from] is the tab on top, so a double tap never stacks two.
 */
fun MutableList<NavKey>.openPayments(from: NavKey) {
    if (lastOrNull() != from || topTab() == null) return
    add(Route.Payments)
}

/**
 * Pushes "Problem with a charge?" over [from], Purchase history (Story 4.17) or Payments (Story 5.8), so Back returns to
 * it. Ignored unless [from] is one of them and on top, so a double tap never stacks two and nothing pushes it over the
 * session lock.
 */
fun MutableList<NavKey>.openProblemWithCharge(from: NavKey) {
    if (lastOrNull() != from || (from != Route.PurchaseHistory && from != Route.Payments)) return
    add(Route.ProblemWithCharge)
}

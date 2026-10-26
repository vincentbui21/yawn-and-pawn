package com.yawnandpawn.app.ui.nav

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.yawnandpawn.app.ui.reliability.ManufacturerGuidanceRoute
import com.yawnandpawn.app.ui.reliability.ReliabilityRoute

/**
 * The entries of the screens pushed over a tab on [backStack] (beside AppNavHost.kt, which keeps the shell): the You
 * tab's Purchase history, Payments and "Problem with a charge?" (Stories 4.16, 4.17 and 5.8, YouEntries.kt), and the
 * reliability checklist (Story 5.4) with the maker guidance over it (5.5), and Progress' Day detail (Story 6.7,
 * ProgressEntries.kt). Back on each closes it.
 */
internal fun EntryProviderScope<NavKey>.pushedEntries(backStack: MutableList<NavKey>) {
    entry<Route.PurchaseHistory> { PurchaseHistoryEntry(backStack) }
    entry<Route.Payments> { PaymentsEntry(backStack) }
    entry<Route.ProblemWithCharge> { ProblemWithChargeEntry(backStack) }
    entry<Route.Reliability> {
        ReliabilityRoute(
            onBack = { backStack.close(Route.Reliability) },
            onOpenManufacturerGuidance = { backStack.openManufacturerGuidance() },
        )
    }
    entry<Route.ManufacturerGuidance> { ManufacturerGuidanceRoute(onClose = { backStack.close(Route.ManufacturerGuidance) }) }
    entry<Route.DayDetail> { route -> DayDetailEntry(backStack, route) }
}

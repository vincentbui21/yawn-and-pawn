package com.yawnandpawn.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import com.yawnandpawn.app.ui.payments.PaymentsRoute
import com.yawnandpawn.app.ui.payments.ProblemWithChargeRoute
import com.yawnandpawn.app.ui.purchases.PurchaseHistoryRoute
import com.yawnandpawn.app.ui.you.YouRoute

// The nav entries of the You tab and the screens it pushes (Stories 4.16, 4.17 and 5.8), for [AppNavHost]. The push
// rules they call are in YouNavigation.kt.

/** The You tab on [backStack] (Story 5.8): its Money rows push Purchase history and Payments over it. */
@Composable
internal fun YouEntry(backStack: MutableList<NavKey>) =
    YouRoute(
        onOpenPurchaseHistory = { backStack.openPurchaseHistory(from = Route.You) },
        onOpenPayments = { backStack.openPayments(from = Route.You) },
    )

/** Purchase history on [backStack]: Back closes it, "Problem with a charge?" opens its screen (Story 4.17). */
@Composable
internal fun PurchaseHistoryEntry(backStack: MutableList<NavKey>) =
    PurchaseHistoryRoute(
        onBack = { backStack.close(Route.PurchaseHistory) },
        onProblemWithCharge = { backStack.openProblemWithCharge(from = Route.PurchaseHistory) },
    )

/**
 * "How payments & refunds work" on [backStack] (Story 5.8): Back closes it, its links push "Problem with a charge?" and
 * Purchase history over it.
 */
@Composable
internal fun PaymentsEntry(backStack: MutableList<NavKey>) =
    PaymentsRoute(
        onBack = { backStack.close(Route.Payments) },
        onProblemWithCharge = { backStack.openProblemWithCharge(from = Route.Payments) },
        onPurchaseHistory = { backStack.openPurchaseHistory(from = Route.Payments) },
    )

/** "Problem with a charge?" on [backStack] (Story 4.17): Back closes it. */
@Composable
internal fun ProblemWithChargeEntry(backStack: MutableList<NavKey>) =
    ProblemWithChargeRoute(onBack = { backStack.close(Route.ProblemWithCharge) })

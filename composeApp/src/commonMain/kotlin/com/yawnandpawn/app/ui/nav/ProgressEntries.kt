package com.yawnandpawn.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import com.yawnandpawn.app.ui.daydetail.DayDetailRoute
import com.yawnandpawn.app.ui.progress.ProgressRoute

// The nav entries of the Progress tab (Story 6.5), for [AppNavHost]: the tab pushes Purchase history and (Story 6.7)
// Day detail over itself.

/**
 * The Progress tab on [backStack]: a chip or a second tap on the selected day pushes Day detail over it, and its
 * "Purchase history" link pushes Purchase history, so Back returns here with its month, scroll and chip.
 */
@Composable
internal fun ProgressEntry(backStack: MutableList<NavKey>) =
    ProgressRoute(
        onOpenDay = { backStack.openDayDetail(it) },
        onOpenPurchaseHistory = { backStack.openPurchaseHistory(from = Route.Progress) },
    )

/** Day detail of [route]'s date (Story 6.7); Back returns to Progress. */
@Composable
internal fun DayDetailEntry(
    backStack: MutableList<NavKey>,
    route: Route.DayDetail,
) = DayDetailRoute(date = route.date, onBack = { backStack.close(route) })

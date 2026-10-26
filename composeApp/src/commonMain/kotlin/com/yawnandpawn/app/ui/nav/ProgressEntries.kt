package com.yawnandpawn.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import com.yawnandpawn.app.ui.progress.ProgressRoute

// The nav entries of the Progress tab (Story 6.5), for [AppNavHost]: the tab pushes Purchase history over itself, and
// (Story 6.7) Day detail.

/** The Progress tab on [backStack]: its "Purchase history" link pushes Purchase history over it, so Back returns here. */
@Composable
internal fun ProgressEntry(backStack: MutableList<NavKey>) =
    ProgressRoute(
        // Story 6.7 pushes Day detail here; until then the chip and a second tap open nothing.
        onOpenDay = { },
        onOpenPurchaseHistory = { backStack.openPurchaseHistory(from = Route.Progress) },
    )

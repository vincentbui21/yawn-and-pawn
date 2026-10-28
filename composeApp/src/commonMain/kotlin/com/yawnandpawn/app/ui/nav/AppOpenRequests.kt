package com.yawnandpawn.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation3.runtime.NavKey
import com.yawnandpawn.app.ui.shell.AppTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.compose.koinInject

/**
 * A request from outside the app's screens to open a tab (Story 6.8: the weekly summary notification opens Progress).
 * The activity [open]s it; the navigation shows the tab once the app is past the restore and onboarding screens, and
 * lets go of it ([handled]) in every case. During a session or onboarding the request is dropped, never deferred: the
 * session lock or onboarding stays on screen, and nothing jumps to Progress afterwards. One app-wide instance (a Koin
 * single).
 */
class AppOpenRequests {
    private val requested = MutableStateFlow<AppTab?>(null)

    /** The tab asked for and not handled yet, or null. */
    val tab: StateFlow<AppTab?> = requested.asStateFlow()

    fun open(tab: AppTab) {
        requested.value = tab
    }

    /** Lets go of the request for [tab]; a newer request for another tab stays. */
    fun handled(tab: AppTab) {
        requested.compareAndSet(tab, null)
    }
}

/**
 * Shows the requested [tab] (Story 6.8): a screen pushed over a tab (the checklist, Purchase history, Payments, ...) is
 * closed first, then [selectTab]. Nothing changes under the session lock or onboarding (no tab in the stack), nor while
 * the editor or the "Problem with a charge?" form is open, so what the user typed is never lost. True when [tab] is
 * shown now (Story 7.11: the skip dialog only shows then).
 */
fun MutableList<NavKey>.openRequested(tab: AppTab): Boolean {
    if (none { (it as? Route)?.tab != null } || any { it is Route.AlarmEditor || it == Route.ProblemWithCharge }) return false
    while (size > 1 && (lastOrNull() as? Route)?.tab == null) removeAt(lastIndex)
    selectTab(tab)
    return true
}

/**
 * Shows each tab [AppOpenRequests] asks for on [backStack] ([openRequested]) and lets go of the request. Call it only
 * once the stack follows the session lock and onboarding (after `shownStack`), so it can never open a tab past them.
 */
@Composable
internal fun OpenRequestedTab(backStack: MutableList<NavKey>) {
    val requests = koinInject<AppOpenRequests>()
    val requested by requests.tab.collectAsState()
    LaunchedEffect(requested) {
        requested?.let { tab ->
            backStack.openRequested(tab)
            requests.handled(tab)
        }
    }
}

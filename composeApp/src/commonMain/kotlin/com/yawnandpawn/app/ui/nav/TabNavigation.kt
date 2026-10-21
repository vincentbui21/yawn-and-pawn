package com.yawnandpawn.app.ui.nav

import androidx.navigation3.runtime.NavKey
import com.yawnandpawn.app.ui.shell.AppTab

// Back stack rules of the shell (Story 1.9). The stack is always [Alarms] or [Alarms, other tab], with at most the
// editor, the reliability checklist (Story 5.4; the maker guidance over it, 5.5), or the You tab's pushed screens on
// top: Purchase history (Story 4.16) or Payments (Story 5.8), Purchase history over Payments, and "Problem with a
// charge?" (4.17) over either of those (their push rules are in YouNavigation.kt). Every change below keeps it that way,
// and a late or repeated call (a double tap) changes nothing. During a session it is only [SessionInProgress] (Story
// 2.6), and until onboarding is completed only [Onboarding] (Story 5.10): no tab is on top then, so the tab, editor and
// push calls below change nothing.

/** The nav-bar tab this route is, or `null` for a pushed screen (the editor, Purchase history, Payments). */
val Route.tab: AppTab?
    get() =
        when (this) {
            Route.Alarms -> AppTab.Alarms
            Route.Progress -> AppTab.Progress
            Route.Settings -> AppTab.Settings
            Route.You -> AppTab.You
            is Route.AlarmEditor -> null
            Route.PurchaseHistory -> null
            Route.Payments -> null
            Route.ProblemWithCharge -> null
            Route.Reliability -> null
            Route.ManufacturerGuidance -> null
            Route.SessionInProgress -> null
            Route.Onboarding -> null
        }

/** The route of [this] tab. */
fun AppTab.route(): Route =
    when (this) {
        AppTab.Alarms -> Route.Alarms
        AppTab.Progress -> Route.Progress
        AppTab.Settings -> Route.Settings
        AppTab.You -> Route.You
    }

/** The tab on top, or `null` while a pushed screen is on top. */
internal fun List<NavKey>.topTab(): AppTab? = (lastOrNull() as? Route)?.tab

/**
 * Shows [tab]: Alarms is the root, so choosing it drops the tab on top; another tab replaces the tab on top. Ignored
 * while the editor is on top or when [tab] is already showing.
 */
fun MutableList<NavKey>.selectTab(tab: AppTab) {
    val top = topTab()
    if (top == null || top == tab) return
    if (top != AppTab.Alarms) removeAt(lastIndex)
    if (tab != AppTab.Alarms) add(tab.route())
}

/**
 * Pushes the editor on [alarmId] (`null`: a new alarm, prefilled from the stored alarm [copyOf] for Duplicate) from
 * any tab. It replaces a tab other than Alarms, so Save or Cancel lands on Home. Ignored while the editor is already on
 * top, so a double tap never stacks two editors.
 */
fun MutableList<NavKey>.openEditor(
    alarmId: String?,
    copyOf: String? = null,
    scanCode: Boolean = false,
) {
    val top = topTab() ?: return
    if (top != AppTab.Alarms) removeAt(lastIndex)
    add(Route.AlarmEditor(alarmId, copyOf, scanCode))
}

/** Closes [route] if it is on top; a late second close never pops the Alarms root. */
fun MutableList<NavKey>.close(route: Route) {
    if (size > 1 && lastOrNull() == route) removeAt(lastIndex)
}

/** Replaces the editor [route] on top with the editor on a new alarm prefilled from the stored alarm [copyOf] (Duplicate). */
fun MutableList<NavKey>.replaceEditor(
    route: Route.AlarmEditor,
    copyOf: String,
) {
    if (lastOrNull() != route) return
    removeAt(lastIndex)
    add(Route.AlarmEditor(alarmId = null, copyOf = copyOf))
}

/**
 * The session lock (Story 2.6, FR-SES-3). While a session is [active], the whole stack is replaced by
 * [Route.SessionInProgress]: an open editor (its draft and any sound preview with it) and every tab leave, and anything
 * pushed later is replaced again. Once the session is over, a locked stack becomes Home ([Route.Alarms]). Otherwise
 * nothing changes. True when the stack changed.
 */
fun MutableList<NavKey>.applySessionLock(active: Boolean): Boolean {
    val locked = size == 1 && single() == Route.SessionInProgress
    return when {
        active && !locked -> replaceWith(Route.SessionInProgress)
        !active && Route.SessionInProgress in this -> replaceWith(Route.Alarms)
        else -> false
    }
}

/**
 * The onboarding gate (Story 5.10, FR-ONB-1). While onboarding is [required], the whole stack is replaced by
 * [Route.Onboarding], like the session lock, so the user cannot reach a tab or the editor before the consent. Once it is
 * completed, an onboarding stack becomes Home ([Route.Alarms]). Otherwise nothing changes. Call it only while the
 * session lock is off: the lock wins. True when the stack changed.
 */
fun MutableList<NavKey>.applyOnboarding(required: Boolean): Boolean {
    val onboarding = size == 1 && single() == Route.Onboarding
    return when {
        required && !onboarding -> replaceWith(Route.Onboarding)
        !required && Route.Onboarding in this -> replaceWith(Route.Alarms)
        else -> false
    }
}

private fun MutableList<NavKey>.replaceWith(route: Route): Boolean {
    clear()
    add(route)
    return true
}

/** Back: pops the top (a tab returns to Alarms); on the Alarms root it does nothing, and the system leaves the app. */
fun MutableList<NavKey>.pop() {
    if (size > 1) removeAt(lastIndex)
}

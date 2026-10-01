package com.yawnandpawn.app.ui.nav

import androidx.navigation3.runtime.NavKey
import com.yawnandpawn.app.ui.shell.AppTab

// Back stack rules of the shell (Story 1.9). The stack is always [Alarms] or [Alarms, other tab], with at most the
// editor on top; every change below keeps it that way, and a late or repeated call (a double tap) changes nothing.

/** The nav-bar tab this route is, or `null` for a pushed screen (the editor). */
val Route.tab: AppTab?
    get() =
        when (this) {
            Route.Alarms -> AppTab.Alarms
            Route.Progress -> AppTab.Progress
            Route.Settings -> AppTab.Settings
            Route.You -> AppTab.You
            is Route.AlarmEditor -> null
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
private fun List<NavKey>.topTab(): AppTab? = (lastOrNull() as? Route)?.tab

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
 * Pushes the editor on [alarmId] (`null`: a new alarm) from any tab. It replaces a tab other than Alarms, so Save or
 * Cancel lands on Home. Ignored while the editor is already on top, so a double tap never stacks two editors.
 */
fun MutableList<NavKey>.openEditor(alarmId: String?) {
    val top = topTab() ?: return
    if (top != AppTab.Alarms) removeAt(lastIndex)
    add(Route.AlarmEditor(alarmId))
}

/** Closes [route] if it is on top; a late second close never pops the Alarms root. */
fun MutableList<NavKey>.close(route: Route) {
    if (size > 1 && lastOrNull() == route) removeAt(lastIndex)
}

/** Replaces the editor [route] on top with the editor on [alarmId] (after Duplicate). */
fun MutableList<NavKey>.replaceEditor(
    route: Route.AlarmEditor,
    alarmId: String,
) {
    if (lastOrNull() != route) return
    removeAt(lastIndex)
    add(Route.AlarmEditor(alarmId))
}

/** Back: pops the top (a tab returns to Alarms); on the Alarms root it does nothing, and the system leaves the app. */
fun MutableList<NavKey>.pop() {
    if (size > 1) removeAt(lastIndex)
}

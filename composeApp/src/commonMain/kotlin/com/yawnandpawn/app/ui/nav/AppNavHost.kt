package com.yawnandpawn.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.yawnandpawn.app.ui.alarms.AlarmsRoute
import com.yawnandpawn.app.ui.editor.AlarmEditorRoute
import com.yawnandpawn.app.ui.theme.PpsTheme

/**
 * The app-screen navigation (Navigation 3): starts on [Route.Alarms]. Each entry gets its own saveable state and
 * ViewModel store, so an editor's ViewModel is cleared when the editor leaves the back stack.
 */
@Composable
fun AppNavHost(modifier: Modifier = Modifier) {
    val backStack = rememberNavBackStack(RouteSavedStateConfiguration, Route.Alarms)
    NavDisplay(
        backStack = backStack,
        modifier = modifier.fillMaxSize().background(PpsTheme.colors.bg),
        onBack = { backStack.pop() },
        entryDecorators =
            listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
        entryProvider =
            entryProvider {
                entry<Route.Alarms> {
                    AlarmsRoute(
                        onAddAlarm = { backStack.openEditor(alarmId = null) },
                        onEditAlarm = { id -> backStack.openEditor(alarmId = id) },
                    )
                }
                entry<Route.AlarmEditor> { route ->
                    AlarmEditorRoute(alarmId = route.alarmId, onClose = { backStack.close(route) })
                }
            },
    )
}

/** Opens the editor from the Alarms route only, so a double tap never stacks two editors. */
private fun NavBackStack<NavKey>.openEditor(alarmId: String?) {
    if (lastOrNull() == Route.Alarms) add(Route.AlarmEditor(alarmId))
}

/** Closes [route] if it is on top; a late second close never pops the Alarms root. */
private fun NavBackStack<NavKey>.close(route: Route) {
    if (size > 1 && lastOrNull() == route) removeAt(lastIndex)
}

private fun NavBackStack<NavKey>.pop() {
    if (size > 1) removeAt(lastIndex)
}

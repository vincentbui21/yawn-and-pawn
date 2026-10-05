package com.yawnandpawn.app.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.ui.components.subScreenTransition
import com.yawnandpawn.app.ui.editor.AlarmEditorRoute
import com.yawnandpawn.app.ui.format.Money
import com.yawnandpawn.app.ui.format.is24HourClock
import com.yawnandpawn.app.ui.home.HomeIntent
import com.yawnandpawn.app.ui.home.HomeRoute
import com.yawnandpawn.app.ui.home.HomeScreen
import com.yawnandpawn.app.ui.home.HomeUiState
import com.yawnandpawn.app.ui.progress.CalendarMonth
import com.yawnandpawn.app.ui.progress.ProgressScreen
import com.yawnandpawn.app.ui.progress.ProgressUiState
import com.yawnandpawn.app.ui.settings.SettingsScreen
import com.yawnandpawn.app.ui.settings.SettingsUiState
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.you.YouScreen
import com.yawnandpawn.app.ui.you.YouUiState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

/**
 * The app-screen navigation (Navigation 3), starting on [Route.Alarms], inside one [AppShell] with the floating nav
 * capsule: it shows while a tab route is on top, with that tab selected (so the selection change animates), and hides
 * under the editor, which slides in and out; switching tabs is instant. Its "+" pushes the editor from any tab. Back on
 * a tab returns to Alarms, Back on Alarms leaves the app (see TabNavigation.kt). Each entry gets its own saveable state
 * (scroll position) and ViewModel store, so an editor's ViewModel is cleared when the editor leaves the back stack.
 *
 * While a wake session is active ([SessionLockGuard]) the stack is only [Route.SessionInProgress] (Story 2.6): Home's
 * approved session state, whose "Back to alarm" opens the wake screen ([WakeScreenOpener]). The editor leaves without a
 * dialog (its ViewModel, draft and preview with it), and Home returns once the session is over.
 */
@Composable
fun AppNavHost(modifier: Modifier = Modifier) {
    val backStack = rememberNavBackStack(RouteSavedStateConfiguration, Route.Alarms)
    SessionLockEffect(backStack)
    // The editor's result for Home: its alarm could not be read, so Home shows "Couldn't open this alarm.".
    var openFailed by rememberSaveable { mutableStateOf(false) }
    val topTab = (backStack.lastOrNull() as? Route)?.tab
    // Under the editor the capsule is hidden; it keeps the tab the stack returns to.
    val selected = topTab ?: backStack.asReversed().firstNotNullOfOrNull { (it as? Route)?.tab } ?: AppTab.Alarms
    AppShell(
        selected = selected,
        onSelect = { backStack.selectTab(it) },
        modifier = modifier,
        onAdd = { backStack.openEditor(alarmId = null) },
        showNavBar = topTab != null,
    ) {
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize().background(PpsTheme.colors.bg),
            onBack = { backStack.pop() },
            entryDecorators =
                listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
            transitionSpec = { screenTransition(forward = true) },
            popTransitionSpec = { screenTransition(forward = false) },
            predictivePopTransitionSpec = { screenTransition(forward = false) },
            entryProvider =
                entryProvider {
                    entry<Route.Alarms>(metadata = TabMetadata) {
                        HomeRoute(
                            onOpenEditor = { id -> backStack.openEditor(id) },
                            onOpenDuplicate = { id -> backStack.openEditor(alarmId = null, copyOf = id) },
                            openFailed = openFailed,
                            onOpenFailedShown = { openFailed = false },
                        )
                    }
                    entry<Route.Progress>(metadata = TabMetadata) { ProgressTab() }
                    entry<Route.Settings>(metadata = TabMetadata) { SettingsTab() }
                    entry<Route.You>(metadata = TabMetadata) { YouTab() }
                    entry<Route.SessionInProgress>(metadata = LockMetadata) { SessionInProgressScreen() }
                    entry<Route.AlarmEditor> { route ->
                        AlarmEditorRoute(
                            alarmId = route.alarmId,
                            copyOf = route.copyOf,
                            onClose = { backStack.close(route) },
                            onOpenFailed = {
                                backStack.close(route)
                                openFailed = true
                            },
                            onOpenCopy = { copyId -> backStack.replaceEditor(route, copyId) },
                        )
                    }
                },
        )
    }
}

/** Marks the tab entries, so moving between two tabs is instant (the shared capsule stays put). */
private const val TAB_ENTRY = "yawnandpawn.tab"
private val TabMetadata: Map<String, Any> = mapOf(TAB_ENTRY to true)

/** Marks the session lock entry: the lock and its end replace the screen at once, with no slide (Story 2.6). */
private const val LOCK_ENTRY = "yawnandpawn.lock"
private val LockMetadata: Map<String, Any> = mapOf(LOCK_ENTRY to true)

/** Home as the session lock shows it: only `panel-session-in-progress` under the header. */
private val SessionLockedHome = HomeUiState(sessionInProgress = true)

/**
 * The session lock (Story 2.6): while a session is active the stack is only [Route.SessionInProgress]; a route pushed
 * then is replaced again after the composition that shows it, and the end of the session returns to Home.
 */
@Composable
private fun SessionLockEffect(backStack: MutableList<NavKey>) {
    val session by koinInject<SessionLockGuard>().state.collectAsState()
    val locked = session != SessionState.Idle
    SideEffect { backStack.applySessionLock(locked) }
}

/** The approved `home-session` screen: the Home header and `panel-session-in-progress`, no nav capsule. */
@Composable
private fun SessionInProgressScreen() {
    val wakeScreen = koinInject<WakeScreenOpener>()
    HomeScreen(
        state = SessionLockedHome,
        is24Hour = is24HourClock(),
        onIntent = { intent -> if (intent == HomeIntent.BackToAlarm) wakeScreen.open() },
    )
}

private fun Scene<NavKey>.isTab(): Boolean = metadata[TAB_ENTRY] == true

private fun Scene<NavKey>.isLock(): Boolean = metadata[LOCK_ENTRY] == true

/**
 * Tab to tab, and into or out of the session lock: instant; to or from the editor: the 250 ms emphasized slide (instant
 * with reduced motion).
 */
private fun AnimatedContentTransitionScope<Scene<NavKey>>.screenTransition(forward: Boolean): ContentTransform {
    val instant = (initialState.isTab() && targetState.isTab()) || initialState.isLock() || targetState.isLock()
    return if (instant) EnterTransition.None togetherWith ExitTransition.None else subScreenTransition(forward)
}

/**
 * Progress until its stories are built (Epic 6): the empty ring with its prompt and this month's empty calendar (no
 * other months to page to), without the Purchase history link.
 */
@Composable
private fun ProgressTab() {
    val clock = koinInject<Clock>()
    val zones = koinInject<TimeZoneProvider>()
    val today = clock.now().toLocalDateTime(zones.current()).date
    val state = remember(today) { emptyProgress(today) }
    ProgressScreen(state = state, onIntent = {}, showPurchaseHistory = false)
}

/** Progress with nothing logged yet on [today]. */
internal fun emptyProgress(today: LocalDate): ProgressUiState =
    ProgressUiState(
        today = today,
        calendar =
            CalendarMonth(
                firstDay = LocalDate(today.year, today.month, 1),
                days = emptyList(),
                today = today,
                hasPrevious = false,
                hasNext = false,
            ),
    )

/** Settings until its stories are built (Epics 4 and 5): the title, no rows. */
@Composable
private fun SettingsTab() {
    val state = remember { SettingsUiState(baseFee = HIDDEN_BASE_FEE) }
    SettingsScreen(state = state, is24Hour = is24HourClock(), onIntent = {}, rows = emptySet())
}

/** The You tab until its stories are built: the title only (every row, About too, opens something not built yet). */
@Composable
private fun YouTab() {
    val state = remember { YouUiState(appVersion = "") }
    YouScreen(state = state, onIntent = {}, rows = emptySet())
}

/** Never shown: the Settings rows that would show the base fee are hidden until Epic 4. */
private val HIDDEN_BASE_FEE = Money(amountMicros = 0, currencyCode = "USD")

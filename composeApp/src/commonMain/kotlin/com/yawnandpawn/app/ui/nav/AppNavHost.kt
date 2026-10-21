package com.yawnandpawn.app.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
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
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.ui.components.subScreenTransition
import com.yawnandpawn.app.ui.editor.AlarmEditorRoute
import com.yawnandpawn.app.ui.format.is24HourClock
import com.yawnandpawn.app.ui.home.HomeIntent
import com.yawnandpawn.app.ui.home.HomeRoute
import com.yawnandpawn.app.ui.home.HomeScreen
import com.yawnandpawn.app.ui.home.HomeUiState
import com.yawnandpawn.app.ui.onboarding.OnboardingRoute
import com.yawnandpawn.app.ui.progress.CalendarMonth
import com.yawnandpawn.app.ui.progress.ProgressScreen
import com.yawnandpawn.app.ui.progress.ProgressUiState
import com.yawnandpawn.app.ui.settings.SettingsRoute
import com.yawnandpawn.app.ui.settings.WeakeningNote
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.PpsTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

/**
 * The app-screen navigation (Navigation 3), starting on [Route.Alarms], inside one [AppShell] with the floating nav
 * capsule: it shows while a tab route is on top, with that tab selected (so the selection change animates), and hides
 * under the editor, which slides in and out; switching tabs is instant. Its "+" pushes the editor from any tab. Back on
 * a tab returns to Alarms, Back on Alarms leaves the app (see TabNavigation.kt). Each entry gets its own saveable state
 * (scroll position) and ViewModel store, so an editor's ViewModel is cleared when the editor leaves the back stack.
 *
 * While a ring, snooze or emergency ring is in progress ([SessionLockGuard.isLocked]) only [Route.SessionInProgress] is
 * shown, from the same composition (Story 2.6): Home's approved session state, whose "Back to alarm" opens the wake
 * screen ([WakeScreenOpener]). The stack itself is replaced right after, so the editor leaves without a dialog (its
 * ViewModel, draft and preview with it), and Home returns once unlocked. Until the stored session is restored (and no
 * emergency ring plays) the app shows a neutral empty screen instead, so a cold start never flashes "Alarm in
 * progress"; the guard still refuses writes then.
 *
 * A "Delete all data" left unfinished (Story 5.9 and its review, `PendingDeletion.pending`: a crash, or a failed run)
 * shows the same neutral screen while `ResumePendingDeletion` finishes it, so nothing is created on top of it; then the
 * empty Home. When the resume gives up the app goes on to Home (the next start tries again). A ring or snooze is never
 * held up: the session lock shows first.
 *
 * Behind the session lock sits the onboarding gate (Story 5.10, [onboardingGate]): until onboarding is completed only
 * [Route.Onboarding] is shown, again from the same composition, with no nav capsule; the stack is replaced right after
 * ([applyOnboarding]) and becomes Home once onboarding is completed. The same neutral screen shows until the stored
 * progress is read, so a first launch never flashes Home.
 */
@Composable
fun AppNavHost(modifier: Modifier = Modifier) {
    val backStack = rememberNavBackStack(RouteSavedStateConfiguration, Route.Alarms)
    val lock = sessionLock()
    val locked = lock == SessionLock.Locked
    if (lock == SessionLock.Restoring || (!locked && pendingDeletion(backStack))) return RestoringScreen(modifier)
    // What is shown follows the lock and the onboarding gate (Story 5.10) in the same composition, so a locked app never
    // composes the route it held (Home or the editor would start their loads and previews), and a new user never sees
    // Home; the real stack is made to match right after.
    val shown = shownStack(backStack, locked) ?: return RestoringScreen(modifier)
    // The editor's result for Home: its alarm could not be read, so Home shows "Couldn't open this alarm.".
    var openFailed by rememberSaveable { mutableStateOf(false) }
    // The editor's other result (Story 4.6): a weakening change waits under the lock, so Home says until when.
    var savedUnderLock by rememberSaveable(stateSaver = WeakeningNoteSaver) { mutableStateOf<WeakeningNote?>(null) }
    val topTab = (shown.lastOrNull() as? Route)?.tab
    // Under the editor the capsule is hidden; it keeps the tab the stack returns to.
    val selected = topTab ?: shown.asReversed().firstNotNullOfOrNull { (it as? Route)?.tab } ?: AppTab.Alarms
    AppShell(
        selected = selected,
        onSelect = { backStack.selectTab(it) },
        modifier = modifier,
        onAdd = { backStack.openEditor(alarmId = null) },
        showNavBar = topTab != null,
    ) {
        NavDisplay(
            backStack = shown,
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
                            savedUnderLock = savedUnderLock,
                            onSavedUnderLockShown = { savedUnderLock = null },
                            onOpenQrRegistration = { id -> backStack.openEditor(id, scanCode = true) },
                            onOpenReliability = { backStack.openReliability(from = Route.Alarms) },
                        )
                    }
                    entry<Route.Progress>(metadata = TabMetadata) { ProgressTab() }
                    entry<Route.Settings>(metadata = TabMetadata) { SettingsTab(backStack) }
                    entry<Route.You>(metadata = TabMetadata) { YouEntry(backStack) }
                    entry<Route.SessionInProgress>(metadata = LockMetadata) { SessionInProgressScreen() }
                    entry<Route.Onboarding>(metadata = LockMetadata) { OnboardingRoute(is24Hour = is24HourClock()) }
                    entry<Route.AlarmEditor> { route ->
                        EditorEntry(route, backStack, onOpenFailed = { openFailed = true }, onSavedUnderLock = { savedUnderLock = it })
                    }
                    pushedEntries(backStack)
                },
        )
    }
}

/** Marks the tab entries, so moving between two tabs is instant (the shared capsule stays put). */
private const val TAB_ENTRY = "yawnandpawn.tab"
private val TabMetadata: Map<String, Any> = mapOf(TAB_ENTRY to true)

/**
 * Marks the session lock entry: the lock and its end replace the screen at once, with no slide (Story 2.6). The
 * onboarding gate (Story 5.10) replaces the whole stack the same way, so its entry carries it too.
 */
private const val LOCK_ENTRY = "yawnandpawn.lock"
private val LockMetadata: Map<String, Any> = mapOf(LOCK_ENTRY to true)

/** Home as the session lock shows it: only `panel-session-in-progress` under the header. */
private val SessionLockedHome = HomeUiState(sessionInProgress = true)

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

/**
 * Settings: the Snooze card's base fee and max snoozes (Story 4.5), the defaults and the theme (5.1), and the reliability
 * checklist row and banner, which push the checklist over Settings on [backStack] (5.4); Epic 5 adds the other rows.
 */
@Composable
private fun SettingsTab(backStack: MutableList<NavKey>) {
    SettingsRoute(is24Hour = is24HourClock(), onOpenReliability = { backStack.openReliability(from = Route.Settings) })
}

/**
 * The editor of [route] on [backStack]; [onOpenFailed] after it closed because its alarm could not be read, and
 * [onSavedUnderLock] when a save left a weakening change waiting under the commitment lock (Story 4.6).
 */
@Composable
private fun EditorEntry(
    route: Route.AlarmEditor,
    backStack: MutableList<NavKey>,
    onOpenFailed: () -> Unit,
    onSavedUnderLock: (WeakeningNote) -> Unit,
) {
    AlarmEditorRoute(
        alarmId = route.alarmId,
        copyOf = route.copyOf,
        scanCode = route.scanCode,
        onClose = { backStack.close(route) },
        onOpenFailed = {
            backStack.close(route)
            onOpenFailed()
        },
        onOpenCopy = { copyId -> backStack.replaceEditor(route, copyId) },
        onSavedUnderLock = onSavedUnderLock,
    )
}

/** Keeps the editor's "Saved. Takes effect after ..." result for Home across a configuration change. */
private val WeakeningNoteSaver: Saver<WeakeningNote?, Any> =
    Saver(
        save = { note -> note?.let { listOf(it.time.toSecondOfDay(), it.today) } },
        restore = { saved ->
            (saved as? List<*>)?.let { WeakeningNote(LocalTime.fromSecondOfDay(it[0] as Int), it[1] as Boolean) }
        },
    )

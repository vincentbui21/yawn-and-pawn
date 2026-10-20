package com.yawnandpawn.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation3.runtime.NavKey
import com.yawnandpawn.app.core.reset.PendingDeletion
import com.yawnandpawn.app.core.reset.ResumePendingDeletion
import org.koin.compose.koinInject

/**
 * After "Delete all data" (Story 5.9): only the empty Home ([Route.Alarms]) is left, every tab and pushed screen gone. A
 * session-locked stack stays as it is (the lock decides what shows then). True when the stack changed.
 */
fun MutableList<NavKey>.resetToHome(): Boolean {
    if (Route.SessionInProgress in this || (size == 1 && single() == Route.Alarms)) return false
    clear()
    add(Route.Alarms)
    return true
}

/**
 * True while an unfinished deletion holds the app (Story 5.9 review, [PendingDeletion.pending]): a crash left it, or a
 * run failed. The app shows the restoring screen meanwhile, so nothing can be created on top of it; this makes sure the
 * resume runs ([ResumePendingDeletion.ensureRunning], app-wide, waiting for the session lock to open) and resets
 * [backStack] to Home, since whatever it held belongs to the data being deleted. Each completed deletion
 * ([PendingDeletion.completed]) resets it too, whichever screen started it, so the event never depends on the You tab
 * still being open. Called only while the app is not session-locked: a ring or snooze always shows first.
 */
@Composable
internal fun pendingDeletion(backStack: MutableList<NavKey>): Boolean {
    val deletion = koinInject<PendingDeletion>()
    val pending by deletion.pending.collectAsState()
    LaunchedEffect(deletion) { deletion.completed.collect { backStack.resetToHome() } }
    if (pending) {
        val resume = koinInject<ResumePendingDeletion>()
        LaunchedEffect(resume) { resume.ensureRunning() }
        SideEffect { backStack.resetToHome() }
    }
    return pending
}

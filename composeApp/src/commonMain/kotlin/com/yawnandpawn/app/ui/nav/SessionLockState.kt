package com.yawnandpawn.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.ui.theme.PpsTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.koin.compose.koinInject

/** The test tag of the neutral screen shown while the stored session is restored. */
const val RESTORING_TAG = "app-restoring"

/** What the app shows for the session lock (Story 2.6). */
internal enum class SessionLock {
    /** Not restored yet and no emergency ring: a neutral empty screen (no "Alarm in progress" flash, no Home). */
    Restoring,

    /** A ring, a snooze or the emergency ring: only "Alarm in progress". */
    Locked,

    /** The app as usual. */
    Unlocked,
}

private fun SessionLockGuard.lock(): SessionLock =
    when {
        !isLocked -> SessionLock.Unlocked
        !restored.value && !emergency.value -> SessionLock.Restoring
        else -> SessionLock.Locked
    }

/**
 * The session lock (Story 2.6, [SessionLockGuard.isLocked]): until the stored session is restored, and while a ring, a
 * snooze or the emergency ring is in progress. Completed and Missed (only the history row pending) do not lock. One
 * flow over all three inputs, read synchronously for the first frame, so a cold start into a session never shows Home
 * first and a cold start without one never shows "Alarm in progress".
 */
@Composable
internal fun sessionLock(): SessionLock {
    val guard = koinInject<SessionLockGuard>()
    val lock by remember(guard) {
        merge(guard.restored, guard.state, guard.emergency).map { guard.lock() }.distinctUntilChanged()
    }.collectAsState(initial = guard.lock())
    return lock
}

/** Nothing to show yet: the app background only (no text, no nav capsule); the back stack is left as it is. */
@Composable
internal fun RestoringScreen(modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize().background(PpsTheme.colors.bg).testTag(RESTORING_TAG))
}

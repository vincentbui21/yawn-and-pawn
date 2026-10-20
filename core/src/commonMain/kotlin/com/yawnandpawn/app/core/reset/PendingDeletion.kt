package com.yawnandpawn.app.core.reset

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a "Delete all data" is unfinished and holds the app (Story 5.9 review, item 1): one instance per process, made
 * from the marker at start ([DeletionMarker.exists]).
 *
 * While [pending] is true:
 * - every guarded write but the deletion's own is refused (`SessionLockGuard.whenIdle`), so nothing is created on top of
 *   a half-done delete;
 * - `AlarmScheduling.rescheduleAll` arms nothing, so a background start (boot, a time change) never re-arms half-deleted
 *   alarms;
 * - the app shows the neutral restoring screen while [ResumePendingDeletion] finishes it (a ring or snooze shows first:
 *   the session lock wins, and nothing here holds up a fire).
 *
 * [DeleteAllData] holds it after a failed run that left the marker, and releases it when a run completes. Then
 * [completed] emits once, so the app goes back to the empty Home whichever screen is open. [ResumePendingDeletion]
 * releases it when it gives up (the marker stays, the next start tries again).
 */
class PendingDeletion(
    initiallyPending: Boolean,
) {
    private val mutablePending = MutableStateFlow(initiallyPending)
    private val completions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** True while an unfinished deletion holds the app (see the class). */
    val pending: StateFlow<Boolean> = mutablePending.asStateFlow()

    /** Emits once each time a deletion completed. */
    val completed: SharedFlow<Unit> = completions.asSharedFlow()

    internal fun hold() {
        mutablePending.value = true
    }

    internal fun release() {
        mutablePending.value = false
    }

    internal fun complete() {
        mutablePending.value = false
        completions.tryEmit(Unit)
    }
}

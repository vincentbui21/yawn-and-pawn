package com.yawnandpawn.app.core.summary

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.flatMap
import com.yawnandpawn.app.core.session.SessionLockGuard

/**
 * The Settings "Weekly summary" switch (Story 6.8, FR-PRG-5): stores [on] and aligns the work at once (off cancels it,
 * on enqueues it at the next Sunday 19:00), no Save. Like every user-facing write the store write runs inside
 * [SessionLockGuard.whenIdle]: during a session it changes nothing and returns [DomainError.SessionActive]. The work is
 * aligned only after the guard is released (review fix), so a ring's `startingSession` never waits for DataStore or
 * WorkManager. A store write that fails touches no work.
 */
class SetWeeklySummary(
    private val store: WeeklySummaryStore,
    private val scheduler: WeeklySummaryScheduler,
    private val sessionLock: SessionLockGuard,
) {
    suspend operator fun invoke(on: Boolean): Outcome<Unit, DomainError> =
        sessionLock.whenIdle { store.setEnabled(on) }.flatMap { scheduler.sync() }
}

package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.session.SessionData

/**
 * The basic Success screen for a completed [session] (Story 3.3): "Test finished. Your alarm works." for a test session,
 * "Up on time." with no snooze, otherwise "You're up. That's what counts.". Streaks arrive in Epic 6 and the amount
 * paid (with the pending-payment note) with Money in Epic 4, so neither is shown yet. Pure.
 */
fun successUiState(session: SessionData): SuccessUiState =
    SuccessUiState(
        kind =
            when {
                session.config.testMode -> SuccessKind.Test
                session.snoozesGranted == 0 -> SuccessKind.OnTime(streakDays = 0)
                else -> SuccessKind.AfterSnooze(paidThisMorning = null)
            },
    )

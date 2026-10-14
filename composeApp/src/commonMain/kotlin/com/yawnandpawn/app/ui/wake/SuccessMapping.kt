package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.session.SessionData

/**
 * The basic Success screen for a completed [session] (Story 3.3): "Test finished. Your alarm works." for a test session,
 * "Up on time." with no snooze, otherwise "You're up. That's what counts." with what the snoozes cost
 * (`SessionData.paid`, Story 4.15). A pending payment that never granted a snooze adds "Your pending payment wasn't
 * used. Google refunds it automatically." (never in a test, which cannot pay). Streaks arrive in Epic 6. Pure.
 */
fun successUiState(session: SessionData): SuccessUiState =
    SuccessUiState(
        kind =
            when {
                session.config.testMode -> SuccessKind.Test
                session.snoozesGranted == 0 -> SuccessKind.OnTime(streakDays = 0)
                else -> SuccessKind.AfterSnooze(paidThisMorning = session.paid)
            },
        pendingNotUsed = session.paymentPending && !session.config.testMode,
    )

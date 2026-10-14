package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.session.SessionData

/**
 * The basic Success screen for a completed [session] (Story 3.3): "Test finished. Your alarm works." for a test session,
 * "Up on time." with no snooze, otherwise "You're up. That's what counts." with what the snoozes cost
 * ([knownPaid], Story 4.15). "Your pending payment wasn't used. Google refunds it automatically." shows only when the
 * session ended with a pending payment and [pendingStillListed]: Play's latest list (the coordinator's recovery query)
 * still holds a pending purchase for this session, so there is something for Google to refund (Story 4.15 review). Never
 * in a test, which cannot pay. Streaks arrive in Epic 6. Pure.
 */
fun successUiState(
    session: SessionData,
    pendingStillListed: Boolean = false,
): SuccessUiState =
    SuccessUiState(
        kind =
            when {
                session.config.testMode -> SuccessKind.Test
                session.snoozesGranted == 0 -> SuccessKind.OnTime(streakDays = 0)
                else -> SuccessKind.AfterSnooze(paidThisMorning = knownPaid(session))
            },
        pendingNotUsed = session.paymentPending && pendingStillListed && !session.config.testMode,
    )

/**
 * What [session]'s snoozes cost, only when the amount of every granted snooze is known (Story 4.15 review): a partial
 * list would show a part of the total as the whole amount, so it is empty then and no amount is shown.
 */
fun knownPaid(session: SessionData): List<Money> = session.paid.takeIf { it.size == session.snoozesGranted }.orEmpty()

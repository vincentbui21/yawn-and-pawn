package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.recording.MotivationPlayback
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.stats.SuccessStreak

/**
 * The Success screen for a completed [session] (Story 3.3): "Test finished. Your alarm works." for a test session, after
 * a snooze "You're up. That's what counts." with what the snoozes cost ([knownPaid], Story 4.15), and with no snooze
 * the zero-snooze [streak] (Story 6.4):
 * - [SuccessStreak.Known] with a streak: the number, "days in a row" and "Up on time.", counting up from the streak
 *   before only when it grew and [celebrate] (the first showing for this session);
 * - [SuccessStreak.Known] with no streak (an earlier paid or missed session today) or [SuccessStreak.Pending]: "Up on
 *   time." only;
 * - [SuccessStreak.Unknown]: "You're up. That's what counts." with no number.
 *
 * "Your pending payment wasn't used. Google refunds it automatically." shows only when the session ended with a pending
 * payment and [pendingStillListed]: Play's latest list (the coordinator's recovery query) still holds a pending purchase
 * for this session, so there is something for Google to refund (Story 4.15 review). Never in a test, which cannot pay.
 *
 * The `motivation-player` card (Story 7.3) shows while the [motivation] player holds this session's message (playing,
 * paused or played to its end); none for another session's or when nothing plays. Pure.
 */
fun successUiState(
    session: SessionData,
    pendingStillListed: Boolean = false,
    streak: SuccessStreak = SuccessStreak.NONE,
    celebrate: Boolean = true,
    motivation: MotivationPlayback? = null,
): SuccessUiState =
    SuccessUiState(
        kind =
            when {
                session.config.testMode -> SuccessKind.Test
                session.snoozesGranted == 0 -> onTime(streak, celebrate)
                else -> SuccessKind.AfterSnooze(paidThisMorning = knownPaid(session))
            },
        pendingNotUsed = session.paymentPending && pendingStillListed && !session.config.testMode,
        motivation = motivationPlayerUi(session, motivation),
    )

private fun onTime(
    streak: SuccessStreak,
    celebrate: Boolean,
): SuccessKind =
    when (streak) {
        SuccessStreak.Unknown -> {
            SuccessKind.UpNoStreak
        }

        SuccessStreak.Pending -> {
            SuccessKind.OnTime(streakDays = 0, countFrom = null)
        }

        is SuccessStreak.Known -> {
            SuccessKind.OnTime(
                streakDays = streak.after.coerceAtLeast(0),
                countFrom = streak.before.takeIf { streak.after > 0 && streak.grew && celebrate },
            )
        }
    }

/** The card of [playback] when it is [session]'s message: progress from 0 to 1 (0 while the length is unknown). */
private fun motivationPlayerUi(
    session: SessionData,
    playback: MotivationPlayback?,
): MotivationPlayerUi? =
    playback?.takeIf { it.sessionId == session.sessionId }?.let {
        MotivationPlayerUi(
            playing = it.playing,
            progress = if (it.durationMs > 0) (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) else 0f,
        )
    }

/**
 * What [session]'s snoozes cost, only when the amount of every granted snooze is known (Story 4.15 review): a partial
 * list would show a part of the total as the whole amount, so it is empty then and no amount is shown.
 */
fun knownPaid(session: SessionData): List<Money> = session.paid.takeIf { it.size == session.snoozesGranted }.orEmpty()

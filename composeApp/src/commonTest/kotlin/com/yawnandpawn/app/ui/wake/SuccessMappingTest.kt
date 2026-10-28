package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.recording.MotivationPlayback
import com.yawnandpawn.app.core.stats.SuccessStreak
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/** Stories 3.3, 4.15 and 6.4: the completed session → Success screen mapping. */
class SuccessMappingTest {
    private fun onTime(
        streak: SuccessStreak,
        celebrate: Boolean = true,
    ): SuccessKind = successUiState(aSession().copy(snoozesGranted = 0), streak = streak, celebrate = celebrate).kind

    @Test
    fun `no snooze and no streak given is Up on time with no number`() {
        assertEquals(
            SuccessUiState(SuccessKind.OnTime(streakDays = 0, countFrom = null)),
            successUiState(aSession().copy(snoozesGranted = 0)),
        )
    }

    @Test
    fun `a streak that grew counts up from the one before, only at the first showing`() {
        assertEquals(SuccessKind.OnTime(streakDays = 12, countFrom = 11), onTime(SuccessStreak.Known(before = 11, after = 12)))
        assertEquals(SuccessKind.OnTime(streakDays = 1, countFrom = 0), onTime(SuccessStreak.Known(before = 0, after = 1)))
        assertEquals(
            SuccessKind.OnTime(streakDays = 12, countFrom = null),
            onTime(SuccessStreak.Known(before = 11, after = 12), celebrate = false),
            "recreated: the final number, still",
        )
    }

    @Test
    fun `a streak that did not grow shows the number with no count-up`() {
        listOf(true, false).forEach { celebrate ->
            assertEquals(
                SuccessKind.OnTime(streakDays = 5, countFrom = null),
                onTime(SuccessStreak.Known(before = 5, after = 5), celebrate),
                "celebrate $celebrate",
            )
        }
    }

    @Test
    fun `no streak after this session, or a read in flight, is Up on time with no number`() {
        listOf(SuccessStreak.Known(before = 6, after = 0), SuccessStreak.NONE, SuccessStreak.Pending).forEach { streak ->
            listOf(true, false).forEach { celebrate ->
                assertEquals(SuccessKind.OnTime(streakDays = 0, countFrom = null), onTime(streak, celebrate), "$streak, $celebrate")
            }
        }
    }

    @Test
    fun `no snapshot is You're up with no number and no paid line`() {
        listOf(true, false).forEach { celebrate ->
            assertEquals(SuccessKind.UpNoStreak, onTime(SuccessStreak.Unknown, celebrate))
        }
    }

    @Test
    fun `the snooze and test paths ignore the streak`() {
        val streaks = listOf(SuccessStreak.Known(before = 3, after = 4), SuccessStreak.Pending, SuccessStreak.Unknown)
        val paid = listOf(Money.of(1, "USD"))
        streaks.forEach { streak ->
            assertEquals(
                SuccessKind.AfterSnooze(paid),
                successUiState(aSession().copy(snoozesGranted = 1, paid = paid), streak = streak).kind,
                "$streak",
            )
            assertEquals(SuccessKind.Test, successUiState(aSession(config = aSessionConfig(testMode = true)), streak = streak).kind)
        }
    }

    @Test
    fun `one or more snoozes is You're up, with no paid line while no amount is known`() {
        listOf(1, 3).forEach { snoozes ->
            assertEquals(
                SuccessUiState(SuccessKind.AfterSnooze(paidThisMorning = emptyList())),
                successUiState(aSession().copy(snoozesGranted = snoozes)),
                "$snoozes snoozes",
            )
        }
    }

    @Test
    fun `after paid snoozes Success carries what they cost, in every currency`() {
        val paid = listOf(Money.of(1, "USD"), Money.of(2, "USD"), Money(1_190_000, "EUR"))

        assertEquals(
            SuccessUiState(SuccessKind.AfterSnooze(paidThisMorning = paid)),
            successUiState(aSession().copy(snoozesGranted = 3, paid = paid)),
        )
    }

    @Test
    fun `a partial list of amounts shows no amount rather than a part of the total`() {
        // Two snoozes, only one price known: "€2.00 paid this morning" would understate what was paid.
        val session = aSession().copy(snoozesGranted = 2, paid = listOf(Money.of(2, "EUR")))

        assertEquals(SuccessUiState(SuccessKind.AfterSnooze(paidThisMorning = emptyList())), successUiState(session))
    }

    @Test
    fun `the pending note shows only while Play still lists a pending purchase for the session, never in a test`() {
        val dollar = listOf(Money.of(1, "USD"))
        val pending = aSession().copy(snoozesGranted = 1, paid = dollar, paymentPending = true)
        assertEquals(
            SuccessUiState(SuccessKind.AfterSnooze(dollar), pendingNotUsed = true),
            successUiState(pending, pendingStillListed = true),
        )
        assertEquals(
            SuccessUiState(SuccessKind.AfterSnooze(dollar)),
            successUiState(pending, pendingStillListed = false),
            "Play no longer lists it (declined, or never charged): no refund to promise",
        )
        assertEquals(
            SuccessUiState(SuccessKind.OnTime(streakDays = 0, countFrom = null), pendingNotUsed = true),
            successUiState(aSession().copy(paymentPending = true), pendingStillListed = true),
        )
        assertEquals(
            SuccessUiState(SuccessKind.OnTime(streakDays = 0, countFrom = null)),
            successUiState(aSession(), pendingStillListed = true),
            "listed, but the session never saw it pending",
        )
        assertEquals(
            SuccessUiState(SuccessKind.Test),
            successUiState(aSession(config = aSessionConfig(testMode = true)).copy(paymentPending = true), pendingStillListed = true),
        )
    }

    @Test
    fun `a test session is Test finished, whatever its snoozes`() {
        listOf(0, 1).forEach { snoozes ->
            val session = aSession(config = aSessionConfig(testMode = true)).copy(snoozesGranted = snoozes)

            assertEquals(SuccessUiState(SuccessKind.Test), successUiState(session), "$snoozes snoozes")
        }
    }

    @Test
    fun `the message card shows the playback of this session only, with its progress (Story 7-3)`() {
        val session = aSession()

        fun card(playback: MotivationPlayback) = successUiState(session, motivation = playback).motivation

        assertEquals(null, successUiState(session).motivation, "nothing plays: no card")
        assertEquals(
            MotivationPlayerUi(playing = true, progress = 0.25f),
            card(MotivationPlayback(session.sessionId, true, positionMs = 2_000, durationMs = 8_000)),
        )
        assertEquals(
            MotivationPlayerUi(playing = false, progress = 1f),
            card(MotivationPlayback(session.sessionId, false, positionMs = 9_000, durationMs = 8_000)),
            "played to its end: full, and play starts it over",
        )
        assertEquals(
            MotivationPlayerUi(playing = true, progress = 0f),
            card(MotivationPlayback(session.sessionId, true)),
            "length not known yet",
        )
        assertEquals(null, card(MotivationPlayback("another-session", true)))
    }
}

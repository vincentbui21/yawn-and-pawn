package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/** Stories 3.3 and 4.15: the completed session → basic Success screen mapping. */
class SuccessMappingTest {
    @Test
    fun `no snooze is Up on time, with no streak before Epic 6`() {
        assertEquals(SuccessUiState(SuccessKind.OnTime(streakDays = 0)), successUiState(aSession().copy(snoozesGranted = 0)))
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
            SuccessUiState(SuccessKind.OnTime(streakDays = 0), pendingNotUsed = true),
            successUiState(aSession().copy(paymentPending = true), pendingStillListed = true),
        )
        assertEquals(
            SuccessUiState(SuccessKind.OnTime(streakDays = 0)),
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
}

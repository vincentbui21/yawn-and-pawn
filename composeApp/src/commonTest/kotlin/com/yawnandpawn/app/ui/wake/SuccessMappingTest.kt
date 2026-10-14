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
    fun `a pending payment that never granted a snooze adds the not used note, never in a test`() {
        val dollar = listOf(Money.of(1, "USD"))
        assertEquals(
            SuccessUiState(SuccessKind.AfterSnooze(dollar), pendingNotUsed = true),
            successUiState(aSession().copy(snoozesGranted = 1, paid = dollar, paymentPending = true)),
        )
        assertEquals(
            SuccessUiState(SuccessKind.OnTime(streakDays = 0), pendingNotUsed = true),
            successUiState(aSession().copy(paymentPending = true)),
        )
        assertEquals(
            SuccessUiState(SuccessKind.Test),
            successUiState(aSession(config = aSessionConfig(testMode = true)).copy(paymentPending = true)),
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

package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/** Story 3.3: the completed session → basic Success screen mapping. */
class SuccessMappingTest {
    @Test
    fun `no snooze is Up on time, with no streak before Epic 6`() {
        assertEquals(SuccessUiState(SuccessKind.OnTime(streakDays = 0)), successUiState(aSession().copy(snoozesGranted = 0)))
    }

    @Test
    fun `one or more snoozes is You're up, with no paid line before Epic 4`() {
        listOf(1, 3).forEach { snoozes ->
            assertEquals(
                SuccessUiState(SuccessKind.AfterSnooze(paidThisMorning = null)),
                successUiState(aSession().copy(snoozesGranted = snoozes)),
                "$snoozes snoozes",
            )
        }
    }

    @Test
    fun `a test session is Test finished, whatever its snoozes`() {
        listOf(0, 1).forEach { snoozes ->
            val session = aSession(config = aSessionConfig(testMode = true)).copy(snoozesGranted = snoozes)

            assertEquals(SuccessUiState(SuccessKind.Test), successUiState(session), "$snoozes snoozes")
        }
    }
}

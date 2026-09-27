package com.yawnandpawn.app.core.alarm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestCodesTest {
    @Test
    fun `the reserved codes are below the alarm range and distinct`() {
        assertEquals(1, RequestCodes.SESSION_SLOT)
        assertEquals(2, RequestCodes.TEST_ALARM)
        assertTrue(RequestCodes.TEST_ALARM < RequestCodes.FIRST_ALARM)
    }

    @Test
    fun `the next alarm code is one above the highest in use, starting at 1000`() {
        val cases =
            listOf(
                emptyList<Int>() to 1000,
                listOf(RequestCodes.SESSION_SLOT, RequestCodes.TEST_ALARM) to 1000,
                listOf(1000) to 1001,
                listOf(1000, 1005, 1002) to 1006,
            )

        cases.forEach { (inUse, expected) -> assertEquals(expected, RequestCodes.nextAlarmCode(inUse), "in use: $inUse") }
    }
}

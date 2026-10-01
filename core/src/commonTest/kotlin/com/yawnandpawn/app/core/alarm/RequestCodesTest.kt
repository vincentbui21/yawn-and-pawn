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
        assertEquals(RequestCodes.FIRST_ALARM - 1, RequestCodes.INITIAL_HIGH_WATER_MARK)
    }

    @Test
    fun `the high-water mark is the highest code in use, at least 999`() {
        val cases =
            listOf(
                emptyList<Int>() to 999,
                listOf(RequestCodes.SESSION_SLOT, RequestCodes.TEST_ALARM) to 999,
                listOf(1000) to 1000,
                listOf(1000, 1005, 1002) to 1005,
            )

        cases.forEach { (inUse, expected) -> assertEquals(expected, RequestCodes.highWaterMark(inUse), "in use: $inUse") }
    }
}

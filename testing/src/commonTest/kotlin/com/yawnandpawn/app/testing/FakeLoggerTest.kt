package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.log.LogEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeLoggerTest {
    @Test
    fun `FakeLogger keeps every event in order`() {
        val logger = FakeLogger()
        val deleted = LogEvent.AlarmDeleted("a", DEFAULT_FAKE_INSTANT)
        val failed = LogEvent.OperationFailed("load alarms", "closed")

        logger.log(deleted)
        logger.log(failed)

        assertEquals(listOf(deleted, failed), logger.events)
    }
}

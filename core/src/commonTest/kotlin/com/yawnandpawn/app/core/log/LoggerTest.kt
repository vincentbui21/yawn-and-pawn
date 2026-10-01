package com.yawnandpawn.app.core.log

import com.yawnandpawn.app.core.alarm.AlarmField
import com.yawnandpawn.app.core.error.DomainError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class LoggerTest {
    @Test
    fun `a logger receives each event it is given`() {
        val received = mutableListOf<LogEvent>()
        val logger = Logger { received += it }
        val deleted = LogEvent.AlarmDeleted("a", Instant.parse("2027-03-03T06:00:00Z"))

        logger.log(deleted)

        assertEquals(listOf<LogEvent>(deleted), received)
    }

    @Test
    fun `a failure is logged with diagnostic text for each domain error`() {
        assertEquals(
            LogEvent.OperationFailed("load alarms", "storage failure: disk I/O error"),
            LogEvent.OperationFailed.of("load alarms", DomainError.StorageFailure("disk I/O error")),
        )
        assertEquals("not found: a", DomainError.NotFound("a").diagnostic())
        assertEquals("invalid alarm field Label", DomainError.InvalidAlarm(AlarmField.Label).diagnostic())
    }
}

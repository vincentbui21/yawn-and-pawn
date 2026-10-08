package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.config.PendingChangePromotion
import com.yawnandpawn.app.core.log.LogEvent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Story 4.4: rescheduleAll() promotes the due pending changes first, and nothing there can keep the alarms from being armed. */
class AlarmSchedulingPromotionTest {
    private val clock = TestClock(Instant.parse("2027-03-08T23:40:00Z"))
    private val repository = InMemoryAlarms()
    private val scheduler = RecordingScheduler()
    private val logger = RecordingLogger()
    private val created = Instant.parse("2027-01-01T00:00:00Z")

    private fun scheduling(promotion: PendingChangePromotion) =
        AlarmScheduling(repository, scheduler, clock, TestZone(TimeZone.UTC), AlarmWriteLock(), logger, promotion)

    @Test
    fun `the promotion runs before the alarms are armed`() =
        runTest {
            repository.alarms.value +=
                "a" to
                Alarm(
                    id = "a",
                    time = LocalTime(7, 30),
                    repeatDays = DayOfWeek.entries.toSet(),
                    requestCode = 1_000,
                    createdAt = created,
                    updatedAt = created,
                )
            val order = mutableListOf<String>()
            scheduler.onCall = { order += "arm" }

            scheduling { order += "promote" }.rescheduleAll()

            assertEquals(listOf("promote", "arm"), order)
        }

    @Test
    fun `an exception in the promotion is logged and the alarms are armed anyway`() =
        runTest {
            repository.alarms.value +=
                "a" to
                Alarm(
                    id = "a",
                    time = LocalTime(7, 30),
                    repeatDays = DayOfWeek.entries.toSet(),
                    requestCode = 1_000,
                    createdAt = created,
                    updatedAt = created,
                )

            scheduling { error("boom") }.rescheduleAll()

            assertEquals(listOf<Call>(Call.Schedule("a", 1_000, Instant.parse("2027-03-09T07:30:00Z"))), scheduler.calls)
            assertTrue(logger.events.contains(LogEvent.OperationFailed("promote pending changes", "IllegalStateException")))
        }
}

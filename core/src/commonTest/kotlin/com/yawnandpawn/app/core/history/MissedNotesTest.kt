package com.yawnandpawn.app.core.history

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** Story 1.16: the Home missed note is the latest Missed session, until it is dismissed. */
class MissedNotesTest {
    private val latest = MutableStateFlow<SessionHistoryRow?>(null)
    private val dismissed = MutableStateFlow<Set<String>>(emptySet())
    private val notes =
        MissedNotes(
            object : SessionHistoryRepository {
                override suspend fun upsert(row: SessionHistoryRow): Outcome<Unit, DomainError> = error("never written here")

                override suspend fun find(sessionId: String): Outcome<SessionHistoryRow?, DomainError> = error("never read here")

                override fun observeLatestMissed(): Flow<SessionHistoryRow?> = latest
            },
            object : MissedNoteDismissals {
                override fun dismissed(): Flow<Set<String>> = dismissed

                override suspend fun dismiss(sessionId: String): Outcome<Unit, DomainError> {
                    dismissed.value += sessionId
                    return Outcome.Success(Unit)
                }
            },
        )

    private fun missed(sessionId: String) =
        SessionHistoryRow(
            sessionId = sessionId,
            alarmId = "alarm-1",
            scheduledAt = Instant.parse("2027-03-03T06:00:00Z"),
            firstRingAt = Instant.parse("2027-03-03T06:00:00Z"),
            endedAt = Instant.parse("2027-03-03T06:30:00Z"),
            snoozeCount = 0,
            checkTypes = listOf("Placeholder"),
            timeToCompleteMs = null,
            fallbackUsed = false,
            directBoot = false,
            outcome = SessionOutcome.Missed,
        )

    @Test
    fun `no Missed session means no note`() =
        runTest {
            assertNull(notes.current().first())
        }

    @Test
    fun `the latest Missed session shows until it is dismissed`() =
        runTest {
            latest.value = missed("s1")
            assertEquals("s1", notes.current().first()?.sessionId)

            assertEquals(Outcome.Success(Unit), notes.dismiss("s1"))

            assertNull(notes.current().first())
        }

    @Test
    fun `a newer Missed session shows although an older one was dismissed`() =
        runTest {
            dismissed.value = setOf("s1")
            latest.value = missed("s2")

            assertEquals("s2", notes.current().first()?.sessionId)
        }
}

package com.yawnandpawn.app.core.recording

import com.yawnandpawn.app.core.alarm.RecordingLogger
import com.yawnandpawn.app.core.alarm.SequentialIds
import com.yawnandpawn.app.core.alarm.TestClock
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.ringSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant

class RecordingUseCasesTest {
    private val now = Instant.parse("2027-03-03T06:00:00.123456Z")
    private val clock = TestClock(now)
    private val ids = SequentialIds()
    private val logger = RecordingLogger()
    private val repository = InMemoryRecordings()
    private val recorder = DiscardingRecorder()
    private val sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    private val sessionLock = SessionLockGuard(sessionState, MutableStateFlow(true), MutableStateFlow(false))
    private val save = SaveRecording(repository, recorder, ids, clock, sessionLock, logger)
    private val reRecord = ReRecord(repository, recorder, sessionLock, logger)
    private val delete = DeleteRecording(repository, sessionLock, logger)

    private fun stored(
        id: String,
        name: String,
        durationMs: Long = 5_000,
    ) = Recording(id, name, durationMs, Recording.fileNameFor(id), Instant.parse("2027-03-01T06:00:00Z"))

    @Test
    fun `the first take is saved as Message 1 with a new id, its own file and the time now in whole milliseconds`() =
        runTest {
            val take = RecordedTake("take-1", 9_000)

            val saved = assertIs<Outcome.Success<Recording>>(save(take)).value

            val expected = Recording("id-1", "Message 1", 9_000, "id-1.m4a", Instant.parse("2027-03-03T06:00:00.123Z"))
            assertEquals(expected, saved)
            assertEquals(listOf(expected to take), repository.saves)
            assertEquals(emptyList(), recorder.discarded)
            assertEquals(emptyList(), logger.events)
        }

    @Test
    fun `the auto name follows the highest Message number and ignores names the user gave`() =
        runTest {
            repository.rows.value = listOf(stored("a", "Message 1"), stored("b", "Message 4"), stored("c", "Morning"))

            assertEquals("Message 5", assertIs<Outcome.Success<Recording>>(save(RecordedTake("t", 2_000))).value.name)
        }

    @Test
    fun `auto names only count exact Message numbers`() {
        assertEquals("Message 1", RecordingNames.next(emptyList()))
        assertEquals("Message 1", RecordingNames.next(listOf("Message", "Message ", "Message x", "Message -3", "message 7")))
        assertEquals("Message 3", RecordingNames.next(listOf("Message 2", "Message 2b", "My Message 9")))
        assertEquals("Message 2147483648", RecordingNames.next(listOf("Message 2147483647")), "no Int overflow")
        assertEquals("Message 1", RecordingNames.next(listOf("Message 9223372036854775807", "Message 99999999999999999999")), "too large")
    }

    @Test
    fun `a take under 1 s is too short, discarded and nothing is stored`() =
        runTest {
            val take = RecordedTake("short", 600)

            assertEquals(Outcome.Failure(DomainError.RecordingTooShort), save(take))

            assertEquals(emptyList(), repository.saves)
            assertEquals(listOf(take), recorder.discarded)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed(SaveRecording.OPERATION, "recording too short")), logger.events)
        }

    @Test
    fun `takes of exactly 1 s and 60 s are kept, a longer one is refused`() =
        runTest {
            assertIs<Outcome.Success<Recording>>(save(RecordedTake("min", 1_000)))
            assertIs<Outcome.Success<Recording>>(save(RecordedTake("max", 60_000)))
            val long = RecordedTake("long", 60_001)

            assertIs<DomainError.RecorderFailure>(assertIs<Outcome.Failure<DomainError>>(save(long)).error)
            assertEquals(listOf(long), recorder.discarded)
        }

    @Test
    fun `a storage failure on save discards the take and logs only the error code`() =
        runTest {
            repository.saveFailure = DomainError.StorageFailure("disk full")
            val take = RecordedTake("t", 3_000)

            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk full")), save(take))

            assertEquals(listOf(take), recorder.discarded)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed(SaveRecording.OPERATION, "storage failure: disk full")), logger.events)
        }

    @Test
    fun `recordings that cannot be read fail the save with the exception class only`() =
        runTest {
            repository.readFails = true
            val take = RecordedTake("t", 3_000)

            assertEquals(Outcome.Failure(DomainError.StorageFailure("read recordings: IllegalStateException")), save(take))
            assertEquals(listOf(take), recorder.discarded)
        }

    @Test
    fun `re-recording keeps the id, name, file and creation time and takes the new duration`() =
        runTest {
            val old = stored("a", "Morning").copy(missing = true)
            repository.rows.value = listOf(old)
            val take = RecordedTake("t", 12_000)

            val updated = assertIs<Outcome.Success<Recording>>(reRecord("a", take)).value

            assertEquals(old.copy(durationMs = 12_000, missing = false), updated)
            assertEquals(listOf(updated to take), repository.saves)
            assertEquals(emptyList(), recorder.discarded)
        }

    @Test
    fun `re-recording an unknown id is NotFound and discards the take`() =
        runTest {
            val take = RecordedTake("t", 12_000)

            assertEquals(Outcome.Failure(DomainError.NotFound("nope")), reRecord("nope", take))

            assertEquals(emptyList(), repository.saves)
            assertEquals(listOf(take), recorder.discarded)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed(ReRecord.OPERATION, "not found: nope")), logger.events)
        }

    @Test
    fun `re-recording with a short take or an unreadable row fails and discards the take`() =
        runTest {
            repository.rows.value = listOf(stored("a", "Morning"))
            val short = RecordedTake("short", 999)
            assertEquals(Outcome.Failure(DomainError.RecordingTooShort), reRecord("a", short))

            repository.getFailure = DomainError.StorageFailure("locked")
            val take = RecordedTake("t", 5_000)
            assertEquals(Outcome.Failure(DomainError.StorageFailure("locked")), reRecord("a", take))

            assertEquals(listOf(short, take), recorder.discarded)
            assertEquals(emptyList(), repository.saves)
        }

    @Test
    fun `delete removes the recording, and an unknown id is NotFound and logged`() =
        runTest {
            repository.rows.value = listOf(stored("a", "Morning"))

            assertEquals(Outcome.Success(Unit), delete("a"))
            assertTrue(repository.rows.value.isEmpty())
            assertEquals(Outcome.Failure(DomainError.NotFound("a")), delete("a"))
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed(DeleteRecording.OPERATION, "not found: a")), logger.events)
        }

    @Test
    fun `nothing is saved, re-recorded or deleted while a wake session is in progress`() =
        runTest {
            repository.rows.value = listOf(stored("a", "Morning"))
            sessionState.value = SessionState.Ringing(ringSession())
            val first = RecordedTake("t1", 3_000)
            val second = RecordedTake("t2", 3_000)

            assertEquals(Outcome.Failure(DomainError.SessionActive), save(first))
            assertEquals(Outcome.Failure(DomainError.SessionActive), reRecord("a", second))
            assertEquals(Outcome.Failure(DomainError.SessionActive), delete("a"))

            assertEquals(listOf(stored("a", "Morning")), repository.rows.value)
            assertEquals(emptyList(), repository.saves)
            assertEquals(listOf(first, second), recorder.discarded, "a take that is not stored is discarded")
        }

    private class InMemoryRecordings : RecordingRepository {
        val rows = MutableStateFlow<List<Recording>>(emptyList())
        val saves = mutableListOf<Pair<Recording, RecordedTake>>()
        var saveFailure: DomainError? = null
        var getFailure: DomainError? = null
        var readFails = false

        override fun observeAll(): Flow<List<Recording>> =
            if (readFails) flow { throw IllegalStateException("secret/path/name.m4a") } else rows.map { it }

        override suspend fun get(id: String): Outcome<Recording?, DomainError> =
            getFailure?.let { Outcome.Failure(it) } ?: Outcome.Success(rows.value.firstOrNull { it.id == id })

        override suspend fun save(
            recording: Recording,
            take: RecordedTake,
        ): Outcome<Unit, DomainError> {
            saveFailure?.let { return Outcome.Failure(it) }
            saves += recording to take
            rows.value = rows.value.filterNot { it.id == recording.id } + recording
            return Outcome.Success(Unit)
        }

        override suspend fun delete(id: String): Outcome<Unit, DomainError> {
            if (rows.value.none { it.id == id }) return Outcome.Failure(DomainError.NotFound(id))
            rows.value = rows.value.filterNot { it.id == id }
            return Outcome.Success(Unit)
        }
    }

    /** Only [discard] is used by the use cases. */
    private class DiscardingRecorder : AudioRecorder {
        val discarded = mutableListOf<RecordedTake>()

        override fun start(): Outcome<RecordingSession, DomainError> = error("not used")

        override suspend fun stop(): Outcome<RecordedTake, DomainError> = error("not used")

        override fun cancel() = Unit

        override fun level(): Flow<Float> = emptyFlow()

        override fun elapsed(): Flow<Duration> = emptyFlow()

        override fun autoStopped(): Flow<Outcome<RecordedTake, DomainError>> = emptyFlow()

        override suspend fun discard(take: RecordedTake) {
            discarded += take
        }
    }
}

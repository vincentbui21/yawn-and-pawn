package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.recording.RecordedTake
import com.yawnandpawn.app.core.recording.RecordingSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration

class RecordingFakesTest {
    @Test
    fun `the fake repository saves, reports missing files and deletes`() =
        runTest {
            val repository = FakeRecordingRepository(listOf(aRecording(id = "a"), aRecording(id = "b", name = "Morning", missing = true)))

            assertEquals(listOf(false, true), repository.observeAll().first().map { it.missing })
            val take = RecordedTake("t", 4_000)
            assertEquals(Outcome.Success(Unit), repository.save(aRecording(id = "b", name = "Morning", durationMs = 4_000), take))
            assertEquals(Outcome.Success(aRecording(id = "b", name = "Morning", durationMs = 4_000)), repository.get("b"))
            assertEquals(take, repository.takes["b"])

            repository.markMissing("a")
            assertEquals(true, (repository.get("a") as Outcome.Success).value?.missing)
            assertEquals(Outcome.Success(Unit), repository.delete("a"))
            assertEquals(Outcome.Failure(DomainError.NotFound("a")), repository.delete("a"))
            assertEquals(Outcome.Success(null), repository.get("a"))
            assertEquals(listOf("b"), repository.stored.map { it.id })

            repository.failure = DomainError.StorageFailure("x")
            assertEquals(Outcome.Failure(DomainError.StorageFailure("x")), repository.get("b"))
            assertEquals(Outcome.Failure(DomainError.StorageFailure("x")), repository.delete("b"))
        }

    @Test
    fun `the fake recorder records one take at a time, stops, auto-stops and discards`() =
        runTest {
            val recorder = FakeAudioRecorder()

            assertEquals(Outcome.Success(RecordingSession("take-1")), recorder.start())
            assertEquals(Outcome.Failure(DomainError.RecorderFailure("already recording")), recorder.start())
            recorder.nextDurationMs = 12_000
            assertEquals(Outcome.Success(RecordedTake("take-1", 12_000)), recorder.stop())
            assertEquals(Outcome.Failure(DomainError.RecorderFailure("not recording")), recorder.stop())

            recorder.start()
            recorder.emitAutoStop()
            assertEquals(Outcome.Success(RecordedTake("take-2", 60_000)), recorder.autoStopped().first())
            assertNull(recorder.recording)
            recorder.emitAutoStop(result = Outcome.Failure(DomainError.RecordingTooShort))
            assertEquals(Outcome.Failure(DomainError.RecordingTooShort), recorder.autoStopped().first())

            recorder.startResult = Outcome.Failure(DomainError.MicrophoneUnavailable)
            assertEquals(Outcome.Failure(DomainError.MicrophoneUnavailable), recorder.start())
            recorder.stopResult = Outcome.Failure(DomainError.RecordingTooShort)
            assertEquals(Outcome.Failure(DomainError.RecordingTooShort), recorder.stop())
            recorder.cancel()
            recorder.discard(RecordedTake("take-2", 60_000))

            assertEquals(4, recorder.starts)
            assertEquals(1, recorder.cancels)
            assertEquals(listOf(RecordedTake("take-2", 60_000)), recorder.discarded)
            assertEquals(0f, recorder.level().first())
            assertEquals(Duration.ZERO, recorder.elapsed().first())
        }
}

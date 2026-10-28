package com.yawnandpawn.app.ui.recordings

import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.Motivation
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.recording.DeleteRecording
import com.yawnandpawn.app.core.recording.PreviewItem
import com.yawnandpawn.app.core.recording.RecordedTake
import com.yawnandpawn.app.core.recording.Recording
import com.yawnandpawn.app.core.recording.RecordingRepository
import com.yawnandpawn.app.core.recording.SaveRecording
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeAudioRecorder
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeIdGenerator
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeRecordingPreview
import com.yawnandpawn.app.testing.FakeRecordingRepository
import com.yawnandpawn.app.testing.FakeReliabilityRecords
import com.yawnandpawn.app.testing.aRecording
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Story 7.2: Recordings' state holder over fakes (recorder, messages, preview, microphone, checklist records). */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingsModelTest {
    private val recorder = FakeAudioRecorder()
    private val recordings = FakeRecordingRepository()
    private val preview = FakeRecordingPreview()
    private val microphone = TestMicrophonePermission()
    private val records = FakeReliabilityRecords()
    private val logger = FakeLogger()
    private val alarms = FakeAlarmRepository()

    /** Saves go through here: [saveGate] holds them while a test needs a save in flight. */
    private var saveGate: CompletableDeferred<Unit>? = null
    private val savingRecordings =
        object : RecordingRepository by recordings {
            override suspend fun save(
                recording: Recording,
                take: RecordedTake,
            ): Outcome<Unit, DomainError> {
                saveGate?.await()
                return recordings.save(recording, take)
            }
        }
    private val sessionLock =
        SessionLockGuard(MutableStateFlow<SessionState>(SessionState.Idle), MutableStateFlow(true), MutableStateFlow(false))
    private val sources =
        RecordingsSources(
            recorder = recorder,
            recordings = recordings,
            saveRecording =
                SaveRecording(
                    savingRecordings,
                    recorder,
                    FakeIdGenerator(),
                    FakeClock(Instant.parse("2027-03-03T06:00:00Z")),
                    sessionLock,
                    logger,
                ),
            deleteRecording = DeleteRecording(recordings, alarms, AlarmWriteLock(), sessionLock, logger),
            preview = preview,
            microphone = microphone,
            records = records,
            logger = logger,
        )
    private val saved = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private var backs = 0

    /** The pane's own scope, cancelled when the pane leaves the composition. */
    private lateinit var pane: CoroutineScope

    private fun TestScope.model(): RecordingsModel =
        RecordingsModel(
            CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job])).also { pane = it },
            sources,
            onSaved = { saved += it },
            onDeleted = { deleted += it },
            onBack = { backs++ },
        ).also {
            it.start()
            runCurrent()
        }

    private val RecordingsModel.recorderState: RecorderState get() = state.value.recorder

    private fun RecordingsModel.recordAndStop(durationMs: Long = 9_000) {
        recorder.nextDurationMs = durationMs
        onIntent(RecordingsIntent.RecordClicked)
    }

    @Test
    fun `the first record tap asks for the microphone, starts recording and is remembered for the checklist`() =
        runTest {
            microphone.granted = false
            microphone.answer = MicrophoneAnswer.Granted
            val model = model()

            model.onIntent(RecordingsIntent.RecordClicked)
            runCurrent()

            assertEquals(1, microphone.requests)
            assertEquals(1, recorder.starts)
            assertTrue(records.stored.recordTapped, "the Microphone row shows from now on")
            assertEquals(0, assertIs<RecorderState.Recording>(model.recorderState).seconds)

            recorder.elapsedTime.value = 9.seconds
            recorder.levels.value = 0.5f
            runCurrent()
            val recording = assertIs<RecorderState.Recording>(model.recorderState)
            assertEquals(9, recording.seconds)
            assertEquals(0.5f, recording.levels.last())
            assertFalse(model.state.value.micDenied)
        }

    @Test
    fun `a granted microphone is not asked again`() =
        runTest {
            val model = model()

            model.onIntent(RecordingsIntent.RecordClicked)
            runCurrent()

            assertEquals(0, microphone.requests)
            assertEquals(1, recorder.starts)
        }

    @Test
    fun `a refused microphone shows the banner with Fix, records nothing, and clears once granted in Settings`() =
        runTest {
            microphone.granted = false
            microphone.answer = MicrophoneAnswer.Blocked
            val model = model()

            model.onIntent(RecordingsIntent.RecordClicked)
            runCurrent()

            assertTrue(model.state.value.micDenied)
            assertEquals(RecorderState.Idle, model.recorderState)
            assertEquals(0, recorder.starts)
            model.onIntent(RecordingsIntent.RecordClicked)
            runCurrent()
            assertEquals(1, microphone.requests, "the record button is disabled: no second dialog")

            model.onIntent(RecordingsIntent.FixMicrophone)
            assertEquals(1, microphone.settingsOpened)
            model.onResumed()
            assertTrue(model.state.value.micDenied, "still off")
            microphone.granted = true
            model.onResumed()
            assertFalse(model.state.value.micDenied)
        }

    @Test
    fun `stop offers the take, and Save adds Message 1 to the list and tells the editor`() =
        runTest {
            val model = model()
            model.recordAndStop()
            runCurrent()

            model.onIntent(RecordingsIntent.StopClicked)
            runCurrent()
            assertEquals(RecorderState.Recorded(seconds = 9), model.recorderState)

            model.onIntent(RecordingsIntent.SaveTakeClicked)
            runCurrent()

            assertEquals(RecorderState.Idle, model.recorderState)
            val message =
                model.state.value.messages
                    .single()
            assertEquals("Message 1", message.name)
            assertEquals(9, message.seconds)
            assertEquals(listOf(message.id), saved)
            assertEquals(listOf(message.id), recordings.stored.map { it.id })
        }

    @Test
    fun `a take under 1 s shows Too short for 4 s and leaves the recorder idle`() =
        runTest {
            val model = model()
            model.recordAndStop()
            runCurrent()
            recorder.stopResult = Outcome.Failure(DomainError.RecordingTooShort)

            model.onIntent(RecordingsIntent.StopClicked)
            advanceTimeBy(1)
            runCurrent()

            assertEquals(RecordingsSnackbar.TooShort, model.state.value.snackbar)
            assertEquals(RecorderState.Idle, model.recorderState)
            advanceTimeBy(RecordingsModel.SNACKBAR_MILLIS)
            runCurrent()
            assertEquals(null, model.state.value.snackbar)
        }

    @Test
    fun `a take the recorder stopped by itself at 60 s is offered like a stopped one`() =
        runTest {
            val model = model()
            model.recordAndStop()
            runCurrent()

            recorder.emitAutoStop()
            runCurrent()

            assertEquals(RecorderState.Recorded(seconds = 60), model.recorderState)
        }

    @Test
    fun `Re-record deletes the take and records again, Delete deletes it`() =
        runTest {
            val model = model()
            model.recordAndStop()
            runCurrent()
            model.onIntent(RecordingsIntent.StopClicked)
            runCurrent()
            val first = RecordedTake("take-1", 9_000)

            model.onIntent(RecordingsIntent.ReRecordClicked)
            runCurrent()
            assertEquals(listOf(first), recorder.discarded)
            assertIs<RecorderState.Recording>(model.recorderState)
            assertEquals(2, recorder.starts)

            model.onIntent(RecordingsIntent.StopClicked)
            runCurrent()
            model.onIntent(RecordingsIntent.DeleteTakeClicked)
            runCurrent()
            assertEquals(listOf(first, RecordedTake("take-2", 9_000)), recorder.discarded)
            assertEquals(RecorderState.Idle, model.recorderState)
            assertTrue(recordings.stored.isEmpty())
        }

    @Test
    fun `previews play one at a time, a tap on the playing one stops it, and a missing file cannot play`() =
        runTest {
            recordings.save(aRecording(id = "r1", name = "Message 1"), RecordedTake("t1", 9_000))
            recordings.save(aRecording(id = "r2", name = "Message 2", durationMs = 14_000), RecordedTake("t2", 14_000))
            recordings.markMissing("r2")
            val model = model()

            model.onIntent(RecordingsIntent.PlayToggled("r1"))
            runCurrent()
            preview.advance(4_200)
            runCurrent()
            val playing =
                model.state.value.messages
                    .first()
            assertTrue(playing.playing)
            assertEquals(4, playing.playedSeconds)

            model.onIntent(RecordingsIntent.PlayToggled("r1"))
            runCurrent()
            assertFalse(
                model.state.value.messages
                    .first()
                    .playing,
            )

            val missing =
                model.state.value.messages
                    .last()
            assertTrue(missing.missing)
            model.onIntent(RecordingsIntent.PlayToggled("r2"))
            runCurrent()
            assertEquals(1, preview.played.size, "a missing file is never played")
        }

    @Test
    fun `the take plays as a preview, and stopping the screen stops it`() =
        runTest {
            val model = model()
            model.recordAndStop()
            runCurrent()
            model.onIntent(RecordingsIntent.StopClicked)
            runCurrent()

            model.onIntent(RecordingsIntent.TakePlayToggled)
            runCurrent()
            assertEquals(PreviewItem.Take(RecordedTake("take-1", 9_000)), preview.played.single())
            assertEquals(RecorderState.Recorded(seconds = 9, playing = true), model.recorderState)

            model.onStopped()
            runCurrent()
            assertEquals(RecorderState.Recorded(seconds = 9, playing = false), model.recorderState)
        }

    @Test
    fun `leaving cancels a recording, deletes an unsaved take and stops the preview`() =
        runTest {
            val model = model()
            model.recordAndStop()
            runCurrent()

            model.leave()
            assertEquals(1, recorder.cancels)

            val again = model()
            again.recordAndStop()
            runCurrent()
            again.onIntent(RecordingsIntent.StopClicked)
            runCurrent()
            again.onIntent(RecordingsIntent.TakePlayToggled)
            runCurrent()

            again.onIntent(RecordingsIntent.Back)
            runCurrent()

            assertEquals(1, backs)
            assertEquals(listOf(RecordedTake("take-2", 9_000)), recorder.discarded)
            assertEquals(null, preview.playing.value)
        }

    @Test
    fun `delete asks first, then removes the message and clears it from the alarms that play it`() =
        runTest {
            recordings.save(aRecording(id = "r1", name = "Message 1"), RecordedTake("t1", 9_000))
            alarms.upsert(anAlarm(id = "a1").copy(motivation = Motivation.Recording("r1")))
            val model = model()

            model.onIntent(RecordingsIntent.DeleteClicked("r1"))
            assertEquals("r1", model.state.value.deleteDialogFor)
            model.onIntent(RecordingsIntent.DeleteCancelled)
            assertEquals(null, model.state.value.deleteDialogFor)
            assertEquals(1, recordings.stored.size, "Keep it keeps it")

            model.onIntent(RecordingsIntent.DeleteClicked("r1"))
            model.onIntent(RecordingsIntent.DeleteConfirmed)
            runCurrent()

            assertTrue(
                model.state.value.messages
                    .isEmpty(),
            )
            assertEquals(listOf("r1"), deleted)
            assertEquals(Motivation.None, alarms.current.single().motivation)
        }

    @Test
    fun `a delete that fails keeps the message, says so, and tells the editor nothing`() =
        runTest {
            recordings.save(aRecording(id = "r1", name = "Message 1"), RecordedTake("t1", 9_000))
            val model = model()
            recordings.failure = DomainError.StorageFailure("disk")

            model.onIntent(RecordingsIntent.DeleteClicked("r1"))
            model.onIntent(RecordingsIntent.DeleteConfirmed)
            runCurrent()

            assertEquals(emptyList(), deleted)
            assertEquals(1, recordings.stored.size)
            assertEquals(RecordingsSnackbar.DeleteFailed, model.state.value.snackbar)
        }

    @Test
    fun `a take that cannot be stored says so, and a recorder that cannot start says so`() =
        runTest {
            val model = model()
            model.recordAndStop()
            runCurrent()
            model.onIntent(RecordingsIntent.StopClicked)
            runCurrent()
            recordings.failure = DomainError.StorageFailure("disk")

            model.onIntent(RecordingsIntent.SaveTakeClicked)
            runCurrent()
            assertEquals(RecordingsSnackbar.SaveFailed, model.state.value.snackbar)
            assertEquals(RecorderState.Idle, model.recorderState)
            assertEquals(emptyList(), saved)

            recorder.startResult = Outcome.Failure(DomainError.RecorderFailure("busy"))
            model.onIntent(RecordingsIntent.RecordClicked)
            runCurrent()
            assertEquals(RecordingsSnackbar.StartFailed, model.state.value.snackbar)
            assertFalse(model.state.value.micDenied, "not a permission problem")
        }

    @Test
    fun `Re-record keeps the old take when the new start fails`() =
        runTest {
            val model = model()
            model.recordAndStop()
            runCurrent()
            model.onIntent(RecordingsIntent.StopClicked)
            runCurrent()
            recorder.startResult = Outcome.Failure(DomainError.RecorderFailure("busy"))

            model.onIntent(RecordingsIntent.ReRecordClicked)
            runCurrent()

            assertEquals(RecorderState.Recorded(seconds = 9), model.recorderState)
            assertEquals(emptyList(), recorder.discarded)
            assertEquals(RecordingsSnackbar.StartFailed, model.state.value.snackbar)
        }

    @Test
    fun `no preview plays while recording or while the take is being saved`() =
        runTest {
            recordings.save(aRecording(id = "r1", name = "Message 1"), RecordedTake("t1", 9_000))
            val model = model()
            model.recordAndStop()
            runCurrent()

            model.onIntent(RecordingsIntent.PlayToggled("r1"))
            runCurrent()
            assertTrue(preview.played.isEmpty(), "the speaker would be recorded")

            model.onIntent(RecordingsIntent.StopClicked)
            runCurrent()
            saveGate = CompletableDeferred()
            model.onIntent(RecordingsIntent.SaveTakeClicked)
            runCurrent()
            model.onIntent(RecordingsIntent.TakePlayToggled)
            model.onIntent(RecordingsIntent.PlayToggled("r1"))
            runCurrent()
            assertTrue(preview.played.isEmpty())
            saveGate?.complete(Unit)
            runCurrent()
        }

    @Test
    fun `leaving while the microphone is being asked never starts a recording`() =
        runTest {
            microphone.granted = false
            val answer = CompletableDeferred<MicrophoneAnswer>()
            microphone.gate = answer
            val model = model()
            model.onIntent(RecordingsIntent.RecordClicked)
            runCurrent()

            model.onIntent(RecordingsIntent.Back)
            microphone.granted = true
            answer.complete(MicrophoneAnswer.Granted)
            runCurrent()

            assertEquals(0, recorder.starts)
            assertEquals(RecorderState.Idle, model.recorderState)
        }

    @Test
    fun `leaving while the take is saved keeps it, and the editor still hears of the new message`() =
        runTest {
            val model = model()
            model.recordAndStop()
            runCurrent()
            model.onIntent(RecordingsIntent.StopClicked)
            runCurrent()
            saveGate = CompletableDeferred()
            model.onIntent(RecordingsIntent.SaveTakeClicked)
            runCurrent()

            model.leave()
            pane.cancel()
            saveGate?.complete(Unit)
            runCurrent()

            assertEquals(emptyList(), recorder.discarded, "the take being saved is never deleted")
            assertEquals(listOf(recordings.stored.single().id), saved)
        }

    @Test
    fun `a take deleted or a message deleted just before leaving still finishes`() =
        runTest {
            recordings.save(aRecording(id = "r1", name = "Message 1"), RecordedTake("t1", 9_000))
            val model = model()
            model.recordAndStop()
            runCurrent()
            model.onIntent(RecordingsIntent.StopClicked)
            runCurrent()

            model.onIntent(RecordingsIntent.DeleteTakeClicked)
            model.onIntent(RecordingsIntent.DeleteClicked("r1"))
            model.onIntent(RecordingsIntent.DeleteConfirmed)
            model.leave()
            pane.cancel()
            runCurrent()

            assertEquals(listOf(RecordedTake("take-1", 9_000)), recorder.discarded)
            assertEquals(listOf("r1"), deleted)
            assertTrue(recordings.stored.isEmpty())
        }
}

/** A scripted [MicrophonePermission]: [granted] now, [answer] to a request (which grants it on Granted). */
class TestMicrophonePermission(
    var granted: Boolean = true,
    var answer: MicrophoneAnswer = MicrophoneAnswer.Denied,
) : MicrophonePermission {
    /** When set, a request waits for it (a dialog still open). */
    var gate: CompletableDeferred<MicrophoneAnswer>? = null
    var requests = 0
        private set
    var settingsOpened = 0
        private set

    override fun isGranted(): Boolean = granted

    override suspend fun request(): MicrophoneAnswer {
        requests++
        gate?.let { return it.await() }
        if (answer == MicrophoneAnswer.Granted) granted = true
        return answer
    }

    override fun openSettings() {
        settingsOpened++
    }
}

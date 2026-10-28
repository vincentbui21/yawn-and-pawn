package com.yawnandpawn.app.ui.editor

import com.yawnandpawn.app.core.alarm.Motivation
import com.yawnandpawn.app.core.alarm.MotivationMode
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.recording.Recording
import com.yawnandpawn.app.core.recording.RecordingRepository
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeNotificationPermission
import com.yawnandpawn.app.testing.FakeRecordingRepository
import com.yawnandpawn.app.testing.FakeRequestCodeSequence
import com.yawnandpawn.app.testing.FakeSoundLibrary
import com.yawnandpawn.app.testing.FakeSoundPreview
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.aRecording
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.ui.home.AlarmActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 7.2: the editor's Motivation row, its sub-screen and the round trip through Recordings; Story 7.5: "When it
 * plays".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmEditorMotivationTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FakeClock(Instant.parse("2027-03-03T06:00:00Z"))
    private val zone = FakeTimeZoneProvider(TimeZone.of("Europe/Helsinki"))
    private val repository = FakeAlarmRepository()
    private val alarms =
        AlarmUseCasesFixture(
            repository = repository,
            clock = clock,
            timeZoneProvider = zone,
            requestCodes = FakeRequestCodeSequence(lastUsed = RequestCodes.FIRST_ALARM),
        )
    private val first = aRecording(id = "r1", name = "Message 1")
    private val second = aRecording(id = "r2", name = "Message 2", durationMs = 14_000)
    private val recordings = FakeRecordingRepository(listOf(first, second))
    private val testAlarmStore = FakeTestAlarmStore()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        alarmId: String? = null,
        recordings: RecordingRepository? = this.recordings,
    ) = AlarmEditorViewModel(
        alarmId = alarmId,
        repository = repository,
        checkConfigs = alarms.checkConfigs,
        saveAlarm = alarms.save,
        clock = clock,
        timeZoneProvider = zone,
        actions = AlarmActions(alarms.setEnabled, alarms.delete, FakeLogger()),
        soundLibrary = FakeSoundLibrary(),
        soundPreview = FakeSoundPreview(),
        notificationPermission = FakeNotificationPermission(),
        testAlarm = ScheduleTestAlarm(FakeAlarmScheduler(), testAlarmStore, clock, FakeLogger()),
        recordings = recordings,
    )

    private val AlarmEditorViewModel.full: FullEditorSections
        get() = assertNotNull(state.value.full)

    @Test
    fun `the app's editor shows the Motivation row with the recordings and when it plays, onboarding's does not`() =
        runTest(dispatcher) {
            val app = viewModel()
            advanceUntilIdle()

            assertTrue(EditorPane.Motivation in app.full.rows)
            assertTrue(app.full.motivationTimingShown, "When it plays shows since Story 7.5 (with a message chosen)")
            assertEquals(MotivationTiming.AfterImUp, app.full.motivationTiming, "After I'm up by default")
            assertEquals(listOf(MotivationRecording("r1", "Message 1"), MotivationRecording("r2", "Message 2")), app.full.recordings)
            assertEquals(MotivationChoice.None, app.full.motivation)

            val onboarding = viewModel(recordings = null)
            advanceUntilIdle()
            assertFalse(EditorPane.Motivation in onboarding.full.rows)
            onboarding.onIntent(EditorIntent.MotivationChosen(MotivationChoice.Recording("r1")))
            assertEquals(MotivationChoice.None, onboarding.state.value.form.motivation, "no Motivation row, no choice")
        }

    @Test
    fun `a chosen message is an unsaved change, saved only on Save, and opens chosen`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onIntent(EditorIntent.PaneOpened(EditorPane.Motivation))

            viewModel.onIntent(EditorIntent.MotivationChosen(MotivationChoice.Recording("r2")))
            assertEquals(MotivationChoice.Recording("r2"), viewModel.full.motivation)
            viewModel.onIntent(EditorIntent.BackRequested)
            assertEquals(EditorPane.Main, viewModel.state.value.pane)
            assertTrue(repository.current.isEmpty(), "nothing is stored before Save")
            viewModel.onIntent(EditorIntent.BackRequested)
            assertTrue(viewModel.state.value.showDiscardDialog, "a new message is an unsaved change")
            viewModel.onIntent(EditorIntent.KeepEditing)

            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            val stored = repository.current.single()
            assertEquals(Motivation.Recording("r2"), stored.motivation)
            val reopened = viewModel(stored.id)
            advanceUntilIdle()
            assertEquals(MotivationChoice.Recording("r2"), reopened.state.value.form.motivation)
            assertEquals(MotivationChoice.Recording("r2"), reopened.full.motivation)
            reopened.onIntent(EditorIntent.BackRequested)
            assertFalse(reopened.state.value.showDiscardDialog, "opened as stored: nothing to discard")
        }

    @Test
    fun `a test ring plays the message chosen now, saved or not`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onIntent(EditorIntent.MotivationChosen(MotivationChoice.Random))

            viewModel.onIntent(EditorIntent.TestAlarmClicked)
            advanceUntilIdle()

            assertEquals(Motivation.Random, assertNotNull(testAlarmStore.pending).motivation)
        }

    @Test
    fun `Record a message opens Recordings, whose Back returns to Motivation`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onIntent(EditorIntent.PaneOpened(EditorPane.Motivation))

            viewModel.onIntent(EditorIntent.RecordMessageClicked)
            assertEquals(EditorPane.Recordings, viewModel.state.value.pane)
            viewModel.onIntent(EditorIntent.BackRequested)
            assertEquals(EditorPane.Motivation, viewModel.state.value.pane)
        }

    @Test
    fun `a message saved in Recordings is never chosen for the user`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onIntent(EditorIntent.RecordingSaved("r3"))
            assertEquals(MotivationChoice.None, viewModel.state.value.form.motivation)
            viewModel.onIntent(EditorIntent.BackRequested)
            assertFalse(viewModel.state.value.showDiscardDialog, "no unsaved change")
        }

    @Test
    fun `a chosen message deleted elsewhere opens as None, with nothing to discard, and Save stores None`() =
        runTest(dispatcher) {
            repository.upsert(anAlarm(id = "a1", time = LocalTime(6, 30)).copy(motivation = Motivation.Recording("gone")))
            val viewModel = viewModel("a1")
            advanceUntilIdle()

            assertEquals(MotivationChoice.None, viewModel.state.value.form.motivation)
            viewModel.onIntent(EditorIntent.BackRequested)
            assertFalse(viewModel.state.value.showDiscardDialog, "dropping it is no unsaved change")
            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()
            assertEquals(Motivation.None, repository.current.single().motivation)
        }

    @Test
    fun `a recordings list that cannot be read never resets the chosen message`() =
        runTest(dispatcher) {
            repository.upsert(anAlarm(id = "a1", time = LocalTime(6, 30)).copy(motivation = Motivation.Recording("r1")))
            val failing =
                object : RecordingRepository by recordings {
                    override fun observeAll() = flow<List<Recording>> { throw IllegalStateException("locked") }
                }
            val viewModel = viewModel("a1", recordings = failing)
            advanceUntilIdle()

            assertEquals(MotivationChoice.Recording("r1"), viewModel.state.value.form.motivation)
        }

    @Test
    fun `a chosen message deleted in Recordings becomes None, with nothing to discard when it was the stored one`() =
        runTest(dispatcher) {
            repository.upsert(anAlarm(id = "a1", time = LocalTime(6, 30)).copy(motivation = Motivation.Recording("r1")))
            val viewModel = viewModel("a1")
            advanceUntilIdle()
            assertEquals(MotivationChoice.Recording("r1"), viewModel.full.motivation)

            // DeleteRecording cleared the stored alarm too; the editor follows it.
            recordings.delete("r1")
            viewModel.onIntent(EditorIntent.RecordingDeleted("r1"))
            advanceUntilIdle()

            assertEquals(MotivationChoice.None, viewModel.full.motivation)
            assertEquals(listOf(MotivationRecording("r2", "Message 2")), viewModel.full.recordings)
            viewModel.onIntent(EditorIntent.BackRequested)
            assertFalse(viewModel.state.value.showDiscardDialog, "the form matches the stored alarm again")

            // Another message deleted leaves a different choice alone.
            viewModel.onIntent(EditorIntent.MotivationChosen(MotivationChoice.Recording("r2")))
            viewModel.onIntent(EditorIntent.RecordingDeleted("r9"))
            assertEquals(MotivationChoice.Recording("r2"), viewModel.state.value.form.motivation)
        }

    @Test
    fun `a missing file is listed as missing`() =
        runTest(dispatcher) {
            recordings.markMissing("r2")
            val viewModel = viewModel()
            advanceUntilIdle()

            assertEquals(MotivationRecording("r2", "Message 2", missing = true), viewModel.full.recordings.last())
        }

    @Test
    fun `an unreadable recordings list shows none and is logged with the exception class only`() =
        runTest(dispatcher) {
            val logger = FakeLogger()
            val failing =
                object : RecordingRepository by recordings {
                    override fun observeAll() =
                        flow<List<Recording>> {
                            throw IllegalStateException("recordings/secret.m4a")
                        }
                }
            val viewModel =
                AlarmEditorViewModel(
                    alarmId = null,
                    repository = repository,
                    checkConfigs = alarms.checkConfigs,
                    saveAlarm = alarms.save,
                    clock = clock,
                    timeZoneProvider = zone,
                    actions = AlarmActions(alarms.setEnabled, alarms.delete, logger),
                    soundLibrary = FakeSoundLibrary(),
                    soundPreview = FakeSoundPreview(),
                    notificationPermission = FakeNotificationPermission(),
                    testAlarm = ScheduleTestAlarm(FakeAlarmScheduler(), testAlarmStore, clock, FakeLogger()),
                    recordings = failing,
                )
            advanceUntilIdle()

            assertEquals(emptyList(), viewModel.full.recordings)
            assertTrue(EditorPane.Motivation in viewModel.full.rows)
            assertTrue(logger.events.none { "secret" in it.toString() }, "${logger.events}")
            assertTrue(logger.events.any { LOAD_RECORDINGS in it.toString() }, "${logger.events}")
        }

    @Test
    fun `Mix into alarm is an unsaved change, saved only on Save, and opens chosen (Story 7-5)`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onIntent(EditorIntent.PaneOpened(EditorPane.Motivation))
            viewModel.onIntent(EditorIntent.MotivationChosen(MotivationChoice.Recording("r1")))
            viewModel.onIntent(EditorIntent.BackRequested)
            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()
            val stored = repository.current.single()
            assertEquals(MotivationMode.AfterImUp, stored.motivationMode, "After I'm up unless changed")

            val editor = viewModel(stored.id)
            advanceUntilIdle()
            editor.onIntent(EditorIntent.PaneOpened(EditorPane.Motivation))
            editor.onIntent(EditorIntent.MotivationTimingSelected(MotivationTiming.MixIntoAlarm))
            assertEquals(MotivationTiming.MixIntoAlarm, editor.full.motivationTiming)
            assertEquals(MotivationTiming.MixIntoAlarm, editor.state.value.form.motivationTiming)
            editor.onIntent(EditorIntent.BackRequested)
            assertEquals(MotivationMode.AfterImUp, repository.current.single().motivationMode, "nothing is stored before Save")
            editor.onIntent(EditorIntent.BackRequested)
            assertTrue(editor.state.value.showDiscardDialog, "a new timing is an unsaved change")
            editor.onIntent(EditorIntent.KeepEditing)

            editor.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            val mixed = repository.current.single()
            assertEquals(MotivationMode.MixIntoAlarm, mixed.motivationMode)
            assertEquals(Motivation.Recording("r1"), mixed.motivation)
            val reopened = viewModel(mixed.id)
            advanceUntilIdle()
            assertEquals(MotivationTiming.MixIntoAlarm, reopened.state.value.form.motivationTiming)
            assertEquals(MotivationTiming.MixIntoAlarm, reopened.full.motivationTiming)
            reopened.onIntent(EditorIntent.BackRequested)
            assertFalse(reopened.state.value.showDiscardDialog, "opened as stored: nothing to discard")
        }

    @Test
    fun `a test ring plays the message the way it is chosen now, saved or not (Story 7-5)`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onIntent(EditorIntent.MotivationChosen(MotivationChoice.Recording("r2")))
            viewModel.onIntent(EditorIntent.MotivationTimingSelected(MotivationTiming.MixIntoAlarm))

            viewModel.onIntent(EditorIntent.TestAlarmClicked)
            advanceUntilIdle()

            val pending = assertNotNull(testAlarmStore.pending)
            assertEquals(Motivation.Recording("r2"), pending.motivation)
            assertEquals(MotivationMode.MixIntoAlarm, pending.motivationMode)
        }

    @Test
    fun `when it plays is kept when the message is deleted, and onboarding's editor ignores it (Story 7-5)`() =
        runTest(dispatcher) {
            repository.upsert(
                anAlarm(id = "a1", time = LocalTime(6, 30))
                    .copy(motivation = Motivation.Recording("r1"), motivationMode = MotivationMode.MixIntoAlarm),
            )
            val viewModel = viewModel("a1")
            advanceUntilIdle()
            assertEquals(MotivationTiming.MixIntoAlarm, viewModel.full.motivationTiming)

            recordings.delete("r1")
            viewModel.onIntent(EditorIntent.RecordingDeleted("r1"))
            advanceUntilIdle()
            assertEquals(MotivationChoice.None, viewModel.full.motivation, "the card hides with no message")
            assertEquals(MotivationTiming.MixIntoAlarm, viewModel.full.motivationTiming, "kept as it is")

            val onboarding = viewModel(recordings = null)
            advanceUntilIdle()
            onboarding.onIntent(EditorIntent.MotivationTimingSelected(MotivationTiming.MixIntoAlarm))
            assertEquals(MotivationTiming.AfterImUp, onboarding.state.value.form.motivationTiming, "no Motivation row, no choice")
        }
}

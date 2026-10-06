package com.yawnandpawn.app.ui.editor

import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAccessibilityState
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeCheckConfigRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeNotificationPermission
import com.yawnandpawn.app.testing.FakeRequestCodeSequence
import com.yawnandpawn.app.testing.FakeSoundLibrary
import com.yawnandpawn.app.testing.FakeSoundPreview
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.checksetup.CheckSetupIntent
import com.yawnandpawn.app.ui.home.AlarmActions
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.MemoryPhase
import com.yawnandpawn.app.ui.wake.WakeIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.yawnandpawn.app.core.checks.CheckType as CoreCheckType
import com.yawnandpawn.app.core.checks.Difficulty as CoreDifficulty

/** Story 3.6: "Try it" from Check setup runs in the editor's state only and leaves the form as it was. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmEditorTryItTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FakeClock(Instant.parse("2027-03-03T06:00:00Z"))
    private val repository = FakeAlarmRepository()
    private val logger = FakeLogger()
    private val scheduler = FakeAlarmScheduler()
    private val testAlarmScheduler = FakeAlarmScheduler()
    private val testStore = FakeTestAlarmStore()
    private val checkRows = FakeCheckConfigRepository(repository)
    private val alarms =
        AlarmUseCasesFixture(
            repository = repository,
            clock = clock,
            requestCodes = FakeRequestCodeSequence(lastUsed = RequestCodes.FIRST_ALARM),
            scheduler = scheduler,
            checkConfigs = checkRows,
        )
    private val seed = 7L
    private val accessibility = FakeAccessibilityState()
    private var seedsDrawn = 0

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        AlarmEditorViewModel(
            alarmId = null,
            repository = repository,
            checkConfigs = alarms.checkConfigs,
            saveAlarm = alarms.save,
            clock = clock,
            timeZoneProvider = FakeTimeZoneProvider(),
            actions = AlarmActions(alarms.setEnabled, alarms.delete, clock, logger),
            soundLibrary = FakeSoundLibrary(),
            soundPreview = FakeSoundPreview(),
            notificationPermission = FakeNotificationPermission(),
            testAlarm = ScheduleTestAlarm(testAlarmScheduler, testStore, clock, logger),
            accessibility = accessibility,
            previewSeed = {
                seedsDrawn++
                seed
            },
        )

    /** The editor on Math's Check setup at [difficulty] and 5 problems. */
    private fun settingUpMath(difficulty: Difficulty): AlarmEditorViewModel =
        viewModel().apply {
            onIntent(EditorIntent.PaneOpened(EditorPane.WakeCheck))
            onIntent(EditorIntent.CheckSetupClicked(CheckType.Math))
            onIntent(EditorIntent.CheckSetup(CheckSetupIntent.DifficultySelected(difficulty)))
            onIntent(EditorIntent.CheckSetup(CheckSetupIntent.CountChanged(5)))
        }

    /** The editor on Math's Check setup at [difficulty], then "Try it". */
    private fun tryingMath(difficulty: Difficulty): AlarmEditorViewModel =
        settingUpMath(difficulty).apply { onIntent(EditorIntent.CheckSetup(CheckSetupIntent.TryItClicked)) }

    private fun answer(difficulty: Difficulty): Int =
        (CoreCheckType.Math.generate(seed, CoreDifficulty.valueOf(difficulty.name), count = 1) as Puzzle.Math).problems.single().answer

    private fun AlarmEditorViewModel.type(value: Int) =
        value.toString().forEach {
            onIntent(EditorIntent.TryIt(WakeIntent.DigitTapped(it.digitToInt())))
        }

    @Test
    fun `Try it opens the preview of the check at the difficulty set, one problem, from the ViewModel's seed`() =
        runTest(dispatcher) {
            val viewModel = tryingMath(Difficulty.Hard)

            val state = viewModel.state.value
            assertEquals(EditorPane.TryIt, state.pane)
            val content = state.tryIt?.content as CheckContent.Math
            assertEquals(1, content.problemCount, "count 1, whatever the check's count")
            assertEquals(3, content.operators.size, "Hard: a × b + c × d")
            assertEquals(1, seedsDrawn)
            assertFalse(state.tryIt!!.done)
        }

    @Test
    fun `a wrong answer shows the wrong state, the right one shows the done state, and Done returns to Check setup`() =
        runTest(dispatcher) {
            val viewModel = settingUpMath(Difficulty.Medium)
            // The form as it was before Try it started (review fix): the preview must not change it.
            val form = viewModel.state.value.form
            viewModel.onIntent(EditorIntent.CheckSetup(CheckSetupIntent.TryItClicked))

            viewModel.type(answer(Difficulty.Medium) + 1)
            viewModel.onIntent(EditorIntent.TryIt(WakeIntent.SubmitAnswer))
            assertTrue(
                (
                    viewModel.state.value.tryIt
                        ?.content as CheckContent.Math
                ).wrong,
            )

            viewModel.type(answer(Difficulty.Medium))
            viewModel.onIntent(EditorIntent.TryIt(WakeIntent.SubmitAnswer))
            assertEquals(
                true,
                viewModel.state.value.tryIt
                    ?.done,
            )

            viewModel.onIntent(EditorIntent.TryIt(WakeIntent.DoneClicked))

            assertEquals(EditorPane.CheckSetup, viewModel.state.value.pane)
            assertNull(viewModel.state.value.tryIt)
            assertEquals(form, viewModel.state.value.form, "the unsaved form is intact")
            assertEquals(
                CheckChip(CheckType.Math, Difficulty.Medium, 5),
                viewModel.state.value.form.checks
                    .single(),
            )
        }

    @Test
    fun `a Memory Sequence preview plays on the ViewModel's clock, 350 ms lit and 150 ms gaps, numbered with TalkBack (Story 3-8)`() =
        runTest(dispatcher) {
            accessibility.screenReaderOn = true
            val viewModel = viewModel()
            viewModel.onIntent(EditorIntent.CheckToggled(CheckType.MemorySequence, selected = true))
            viewModel.onIntent(EditorIntent.CheckSetupClicked(CheckType.MemorySequence))
            assertEquals(
                true,
                viewModel.state.value
                    .checkSetupState()
                    ?.talkBackOn,
                "Uses numbered tiles with TalkBack.",
            )
            viewModel.onIntent(EditorIntent.CheckSetup(CheckSetupIntent.TryItClicked))
            val sequence =
                (CoreCheckType.MemorySequence(numbered = true).generate(seed, CoreDifficulty.Medium, 1) as Puzzle.Memory).rounds.single()

            fun memory() =
                viewModel.state.value.tryIt
                    ?.content as CheckContent.MemorySequence
            assertTrue(memory().numbered)
            assertEquals(MemoryPhase.Watch to sequence[0], memory().phase to memory().litTile)
            advanceTimeBy(349)
            assertEquals(sequence[0], memory().litTile, "still lit at 349 ms")
            advanceTimeBy(2)
            assertNull(memory().litTile, "the gap")
            advanceTimeBy(150)
            assertEquals(sequence[1], memory().litTile)
            advanceUntilIdle()
            assertEquals(MemoryPhase.YourTurn, memory().phase)

            viewModel.onIntent(EditorIntent.TryIt(WakeIntent.TileTapped(sequence[0])))
            viewModel.onIntent(EditorIntent.BackRequested)
            advanceUntilIdle()
            assertEquals(EditorPane.CheckSetup, viewModel.state.value.pane)
            assertNull(viewModel.state.value.tryIt, "no tick brings it back")
        }

    @Test
    fun `Back from the preview returns to Check setup, then the Wake-up check, with the form as it was before Try it`() =
        runTest(dispatcher) {
            val viewModel = settingUpMath(Difficulty.Easy)
            val form = viewModel.state.value.form
            viewModel.onIntent(EditorIntent.CheckSetup(CheckSetupIntent.TryItClicked))
            viewModel.type(4)

            viewModel.onIntent(EditorIntent.BackRequested)
            assertEquals(EditorPane.CheckSetup, viewModel.state.value.pane)
            assertNull(viewModel.state.value.tryIt, "the preview and its typed answer are gone")
            assertEquals(form, viewModel.state.value.form, "the practice answer never reaches the form")
            viewModel.onIntent(EditorIntent.BackRequested)

            assertEquals(EditorPane.WakeCheck, viewModel.state.value.pane)
            assertEquals(form, viewModel.state.value.form)
        }

    @Test
    fun `a preview stores, schedules and arms nothing, and a tap outside one does nothing`() =
        runTest(dispatcher) {
            val viewModel = tryingMath(Difficulty.Medium)
            viewModel.type(answer(Difficulty.Medium))
            viewModel.onIntent(EditorIntent.TryIt(WakeIntent.SubmitAnswer))
            viewModel.onIntent(EditorIntent.TryIt(WakeIntent.DoneClicked))
            advanceUntilIdle()

            viewModel.onIntent(EditorIntent.TryIt(WakeIntent.DigitTapped(1)))
            assertNull(viewModel.state.value.tryIt, "no preview is running")
            assertTrue(repository.current.isEmpty(), "no alarm stored")
            assertTrue(checkRows.current.isEmpty(), "no check stored, for any alarm")
            assertEquals(emptyList(), scheduler.calls, "nothing scheduled")
            assertEquals(emptyList(), testAlarmScheduler.calls, "no test alarm armed")
            assertNull(testStore.pending, "no test ring stored")
        }
}

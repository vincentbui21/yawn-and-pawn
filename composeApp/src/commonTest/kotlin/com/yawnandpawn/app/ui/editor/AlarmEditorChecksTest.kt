package com.yawnandpawn.app.ui.editor

import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.alarm.orderedEntries
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
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
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.testing.checkConfigsOf
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.checksetup.CheckSetupIntent
import com.yawnandpawn.app.ui.home.AlarmActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.yawnandpawn.app.core.checks.CheckMode as CoreCheckMode
import com.yawnandpawn.app.core.checks.CheckType as CoreCheckType
import com.yawnandpawn.app.core.checks.Difficulty as CoreDifficulty

/** Story 3.5: the editor's Wake-up check sub-screen and Check setup, in the form until Save. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmEditorChecksTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FakeClock(Instant.parse("2027-03-03T06:00:00Z"))
    private val repository = FakeAlarmRepository()
    private val logger = FakeLogger()
    private val checkRows = FakeCheckConfigRepository(repository)
    private val alarms =
        AlarmUseCasesFixture(
            repository = repository,
            clock = clock,
            requestCodes = FakeRequestCodeSequence(lastUsed = RequestCodes.FIRST_ALARM),
            checkConfigs = checkRows,
        )

    private val math = CheckChip(CheckType.Math, Difficulty.Medium, 3)

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
        pickable: List<CheckType> = listOf(CheckType.Math),
    ) = AlarmEditorViewModel(
        alarmId = alarmId,
        repository = repository,
        checkConfigs = alarms.checkConfigs,
        saveAlarm = alarms.save,
        clock = clock,
        timeZoneProvider = FakeTimeZoneProvider(),
        actions = AlarmActions(alarms.setEnabled, alarms.delete, clock, logger),
        soundLibrary = FakeSoundLibrary(),
        soundPreview = FakeSoundPreview(),
        notificationPermission = FakeNotificationPermission(),
        testAlarm = ScheduleTestAlarm(FakeAlarmScheduler(), FakeTestAlarmStore(), clock, logger),
        pickable = pickable,
    )

    private fun TestScope.effectsOf(viewModel: AlarmEditorViewModel): List<EditorEffect> {
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
        return effects
    }

    private fun AlarmEditorViewModel.checks(): List<CheckChip> = state.value.form.checks

    @Test
    fun `a new alarm starts with Random Math Medium 3, shown on the Wake-up check row only, offering the pickable checks`() =
        runTest(dispatcher) {
            val full = viewModel().state.value.full

            assertEquals(listOf(math), full?.checks)
            assertEquals(CheckMode.Random, full?.checkMode)
            assertEquals(setOf(EditorPane.WakeCheck), full?.rows)
            assertEquals(listOf(CheckType.Math), full?.types)
        }

    @Test
    fun `ticking adds a check last at Medium with its default count, unticking removes it, and an unoffered check is ignored`() =
        runTest(dispatcher) {
            val viewModel = viewModel(pickable = listOf(CheckType.Math, CheckType.WordUnscramble))

            viewModel.onIntent(EditorIntent.CheckToggled(CheckType.WordUnscramble, selected = true))
            assertEquals(listOf(math, CheckChip(CheckType.WordUnscramble, Difficulty.Medium, 2)), viewModel.checks())

            viewModel.onIntent(EditorIntent.CheckToggled(CheckType.QrBarcode, selected = true))
            viewModel.onIntent(EditorIntent.CheckToggled(CheckType.Math, selected = true))
            assertEquals(2, viewModel.checks().size, "not offered, or already there")

            viewModel.onIntent(EditorIntent.CheckToggled(CheckType.Math, selected = false))
            assertEquals(listOf(CheckType.WordUnscramble), viewModel.checks().map { it.type })
            assertEquals(
                viewModel.checks(),
                viewModel.state.value.full
                    ?.checks,
                "the row and sub-screen follow the form",
            )
        }

    @Test
    fun `move up and down change the order, and do nothing at either end`() =
        runTest(dispatcher) {
            val viewModel = viewModel(pickable = listOf(CheckType.Math, CheckType.WordUnscramble))
            viewModel.onIntent(EditorIntent.CheckToggled(CheckType.WordUnscramble, selected = true))
            viewModel.onIntent(EditorIntent.CheckModeSelected(CheckMode.All))

            viewModel.onIntent(EditorIntent.CheckMoved(CheckType.WordUnscramble, up = true))
            assertEquals(listOf(CheckType.WordUnscramble, CheckType.Math), viewModel.checks().map { it.type })

            viewModel.onIntent(EditorIntent.CheckMoved(CheckType.WordUnscramble, up = true))
            viewModel.onIntent(EditorIntent.CheckMoved(CheckType.Math, up = false))
            assertEquals(listOf(CheckType.WordUnscramble, CheckType.Math), viewModel.checks().map { it.type }, "already at the ends")

            viewModel.onIntent(EditorIntent.CheckMoved(CheckType.Math, up = true))
            assertEquals(listOf(CheckType.Math, CheckType.WordUnscramble), viewModel.checks().map { it.type })
            assertEquals(
                CheckMode.All,
                viewModel.state.value.full
                    ?.checkMode,
            )
        }

    @Test
    fun `Check setup sets the difficulty and the count within Math's 1 to 10, and Back returns to the Wake-up check`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onIntent(EditorIntent.PaneOpened(EditorPane.WakeCheck))
            viewModel.onIntent(EditorIntent.CheckSetupClicked(CheckType.Math))
            assertEquals(EditorPane.CheckSetup, viewModel.state.value.pane)
            assertEquals(
                1..10,
                viewModel.state.value
                    .checkSetupState()
                    ?.countRange,
            )

            viewModel.onIntent(EditorIntent.CheckSetup(CheckSetupIntent.DifficultySelected(Difficulty.Hard)))
            viewModel.onIntent(EditorIntent.CheckSetup(CheckSetupIntent.CountChanged(11)))
            assertEquals(CheckChip(CheckType.Math, Difficulty.Hard, 10), viewModel.checks().single())
            viewModel.onIntent(EditorIntent.CheckSetup(CheckSetupIntent.CountChanged(0)))
            assertEquals(1, viewModel.checks().single().count)

            viewModel.onIntent(EditorIntent.CheckSetup(CheckSetupIntent.Back))
            assertEquals(EditorPane.WakeCheck, viewModel.state.value.pane)
            viewModel.onIntent(EditorIntent.BackRequested)
            assertEquals(EditorPane.Main, viewModel.state.value.pane)
            assertFalse(viewModel.state.value.showDiscardDialog, "Back from a sub-screen never asks")
        }

    @Test
    fun `with no check Save shows Pick at least one check and stores nothing, and a ticked check clears it`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val effects = effectsOf(viewModel)
            viewModel.onIntent(EditorIntent.CheckToggled(CheckType.Math, selected = false))

            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            assertEquals(
                true,
                viewModel.state.value.full
                    ?.noCheckError,
            )
            assertTrue(repository.current.isEmpty(), "nothing stored")
            assertEquals(emptyList(), effects)
            assertFalse(viewModel.state.value.isSaving)

            viewModel.onIntent(EditorIntent.CheckToggled(CheckType.Math, selected = true))
            assertEquals(
                false,
                viewModel.state.value.full
                    ?.noCheckError,
            )
        }

    @Test
    fun `Save stores the checks in order and the mode with the alarm`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onIntent(EditorIntent.CheckSetupClicked(CheckType.Math))
            viewModel.onIntent(EditorIntent.CheckSetup(CheckSetupIntent.DifficultySelected(Difficulty.Easy)))
            viewModel.onIntent(EditorIntent.CheckModeSelected(CheckMode.All))

            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            val alarm = repository.current.single()
            assertEquals(CoreCheckMode.All, alarm.checkMode)
            assertEquals(
                listOf(CheckEntry(CoreCheckType.Math, CoreDifficulty.Easy, 3)),
                (alarms.checkConfigs.forAlarm(alarm.id) as Outcome.Success).value.orderedEntries(),
            )
        }

    @Test
    fun `a stored alarm opens with its checks and mode, and a check change is an unsaved change`() =
        runTest(dispatcher) {
            val stored = anAlarm(id = "a").copy(checkMode = CoreCheckMode.All)
            alarms.checkConfigs.saveWithAlarm(stored, checkConfigsOf("a", listOf(CheckEntry(CoreCheckType.Math, CoreDifficulty.Hard, 6))))
            val viewModel = viewModel(alarmId = "a")
            advanceUntilIdle()

            assertEquals(listOf(CheckChip(CheckType.Math, Difficulty.Hard, 6)), viewModel.checks())
            assertEquals(CheckMode.All, viewModel.state.value.form.checkMode)
            viewModel.onIntent(EditorIntent.BackRequested)
            assertFalse(viewModel.state.value.showDiscardDialog, "nothing changed yet")

            viewModel.onIntent(EditorIntent.CheckModeSelected(CheckMode.Random))
            viewModel.onIntent(EditorIntent.BackRequested)

            assertTrue(viewModel.state.value.showDiscardDialog, "a check change asks Discard changes?")
        }

    @Test
    fun `a stored alarm without check rows opens with the default checks`() =
        runTest(dispatcher) {
            repository.upsert(anAlarm(id = "a"))

            val viewModel = viewModel(alarmId = "a")
            advanceUntilIdle()

            assertEquals(EditorForm.DEFAULT_CHECKS, viewModel.checks())
            assertEquals(listOf(math), EditorForm.DEFAULT_CHECKS)
            assertNull(viewModel.state.value.setupType)
            assertEquals(CheckConfig.DEFAULT_ENTRIES.single().count, math.count)
        }

    @Test
    fun `a stored count outside the type's range opens as the nearest valid count (review fix)`() =
        runTest(dispatcher) {
            val rows = listOf(CheckEntry(CoreCheckType.Math, CoreDifficulty.Hard, 15))
            checkRows.saveWithAlarm(anAlarm(id = "a"), checkConfigsOf("a", rows))

            val viewModel = viewModel(alarmId = "a")
            advanceUntilIdle()

            assertEquals(listOf(CheckChip(CheckType.Math, Difficulty.Hard, 10)), viewModel.checks())
        }

    @Test
    fun `a stored check the editor cannot show is left out explicitly, Back asks and Save stores what is shown (review fix)`() =
        runTest(dispatcher) {
            val rows =
                listOf(
                    CheckEntry(CoreCheckType.Placeholder, CoreDifficulty.Easy, 1),
                    CheckEntry(CoreCheckType.Math, CoreDifficulty.Hard, 4),
                )
            checkRows.saveWithAlarm(anAlarm(id = "a"), checkConfigsOf("a", rows))
            val viewModel = viewModel(alarmId = "a")
            advanceUntilIdle()

            assertEquals(listOf(CheckChip(CheckType.Math, Difficulty.Hard, 4)), viewModel.checks())
            viewModel.onIntent(EditorIntent.BackRequested)
            assertTrue(viewModel.state.value.showDiscardDialog, "the dropped check counts as a change")
            viewModel.onIntent(EditorIntent.KeepEditing)

            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            assertEquals(
                listOf(CheckEntry(CoreCheckType.Math, CoreDifficulty.Hard, 4)),
                (checkRows.forAlarm("a") as Outcome.Success).value.orderedEntries(),
            )
        }

    @Test
    fun `with only a check that has no core plugin Save shows Pick at least one check, not a save failure (review fix)`() =
        runTest(dispatcher) {
            val viewModel = viewModel(pickable = listOf(CheckType.Math, CheckType.QrBarcode))
            val effects = effectsOf(viewModel)
            viewModel.onIntent(EditorIntent.CheckToggled(CheckType.QrBarcode, selected = true))
            viewModel.onIntent(EditorIntent.CheckToggled(CheckType.Math, selected = false))

            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            assertEquals(
                true,
                viewModel.state.value.full
                    ?.noCheckError,
            )
            assertTrue(repository.current.isEmpty(), "nothing stored")
            assertEquals(emptyList(), effects, "no Couldn't save snackbar")
        }

    @Test
    fun `the production editor offers the pickable checks in order (review fix)`() =
        runTest(dispatcher) {
            val viewModel =
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
                    testAlarm = ScheduleTestAlarm(FakeAlarmScheduler(), FakeTestAlarmStore(), clock, logger),
                )

            assertEquals(
                listOf(CheckType.Math, CheckType.WordUnscramble, CheckType.MemorySequence),
                viewModel.state.value.full
                    ?.types,
            )
        }

    @Test
    fun `checks that cannot be read close the editor with OpenFailed and are logged, never silent defaults (review fix)`() =
        runTest(dispatcher) {
            repository.upsert(anAlarm(id = "a"))
            checkRows.failure = DomainError.StorageFailure("disk I/O error")
            val viewModel = viewModel(alarmId = "a")

            assertEquals(EditorEffect.OpenFailed, viewModel.effects.first())
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("open alarm", "storage failure: disk I/O error")), logger.events)
        }
}

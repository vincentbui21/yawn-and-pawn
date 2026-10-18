package com.yawnandpawn.app.ui.editor

import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.core.session.ThemeMode
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeGlobalSettingsRepository
import com.yawnandpawn.app.testing.FakeIdGenerator
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeNotificationPermission
import com.yawnandpawn.app.testing.FakeRequestCodeSequence
import com.yawnandpawn.app.testing.FakeSoundLibrary
import com.yawnandpawn.app.testing.FakeSoundPreview
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.ui.home.AlarmActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 5.1: "+" opens a new alarm with the Settings defaults (snooze length, quiet time, its vibration) and no unsaved
 * changes; an edit and a Duplicate keep the stored alarm's own values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmEditorDefaultsTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FakeClock(Instant.parse("2027-03-03T06:00:00Z"))
    private val zone = FakeTimeZoneProvider(TimeZone.UTC)
    private val repository = FakeAlarmRepository()
    private val logger = FakeLogger()
    private val alarms =
        AlarmUseCasesFixture(
            repository = repository,
            clock = clock,
            timeZoneProvider = zone,
            ids = FakeIdGenerator(),
            requestCodes = FakeRequestCodeSequence(lastUsed = RequestCodes.FIRST_ALARM),
            scheduler = FakeAlarmScheduler(),
        )
    private val settings =
        FakeGlobalSettingsRepository(
            GlobalSettings(
                defaultSnoozeLengthMinutes = 15,
                defaultGraceSeconds = 30,
                defaultVibrateInGrace = false,
                themeMode = ThemeMode.Dark,
            ),
        )

    private val stored =
        anAlarm(id = "stored", time = LocalTime(6, 30)).copy(snoozeLengthMinutes = 5, graceSeconds = 25, vibrateInGrace = true)

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
        copyOf: String? = null,
    ) = AlarmEditorViewModel(
        alarmId = alarmId,
        repository = repository,
        checkConfigs = alarms.checkConfigs,
        saveAlarm = alarms.save,
        clock = clock,
        timeZoneProvider = zone,
        actions = AlarmActions(alarms.setEnabled, alarms.delete, logger),
        soundLibrary = FakeSoundLibrary(),
        soundPreview = FakeSoundPreview(),
        notificationPermission = FakeNotificationPermission(),
        testAlarm = ScheduleTestAlarm(FakeAlarmScheduler(), FakeTestAlarmStore(), clock, logger),
        copyOf = copyOf,
        newAlarmDefaults = settings,
    )

    private fun TestScope.effectsOf(viewModel: AlarmEditorViewModel): List<EditorEffect> {
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
        return effects
    }

    @Test
    fun `a new alarm shows only the background until the defaults are read, then opens with them and no unsaved changes`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val effects = effectsOf(viewModel)
            assertTrue(viewModel.state.value.isLoading, "nothing to edit before the defaults are read")

            advanceUntilIdle()

            val state = viewModel.state.value
            assertFalse(state.isLoading)
            assertTrue(state.isNew)
            assertEquals(15, state.form.snoozeLengthMinutes)
            assertEquals(30, state.form.graceSeconds)
            assertFalse(state.form.vibrateInGrace)
            assertEquals(EditorForm().time, state.form.time, "the rest is the built-in new alarm")
            assertEquals(EditorForm().checks, state.form.checks)

            viewModel.onIntent(EditorIntent.BackRequested)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.showDiscardDialog, "no unsaved changes")
            assertEquals(listOf<EditorEffect>(EditorEffect.Close), effects)
        }

    @Test
    fun `the new alarm is saved with the defaults`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            val saved = repository.current.single()
            assertEquals(15, saved.snoozeLengthMinutes)
            assertEquals(30, saved.graceSeconds)
            assertFalse(saved.vibrateInGrace)
        }

    @Test
    fun `an unreadable store is logged and the new alarm opens with the built-in defaults`() =
        runTest(dispatcher) {
            settings.failure = DomainError.StorageFailure("disk unreadable")
            val viewModel = viewModel()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertFalse(state.isLoading)
            assertEquals(EditorForm(), state.form)
            assertTrue(logger.events.any { it is LogEvent.OperationFailed && it.operation == "load new-alarm defaults" })
        }

    @Test
    fun `an existing alarm keeps its own values`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            val viewModel = viewModel(alarmId = "stored")
            advanceUntilIdle()

            val form = viewModel.state.value.form
            assertEquals(5, form.snoozeLengthMinutes)
            assertEquals(25, form.graceSeconds)
            assertTrue(form.vibrateInGrace)
        }

    @Test
    fun `a duplicate keeps the copied alarm's values`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            val viewModel = viewModel(copyOf = "stored")
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.isNew)
            assertEquals(5, state.form.snoozeLengthMinutes)
            assertEquals(25, state.form.graceSeconds)
            assertTrue(state.form.vibrateInGrace)
        }

    @Test
    fun `a stored default the form cannot take opens as the built-in one`() {
        val form = newAlarmForm(GlobalSettings(defaultSnoozeLengthMinutes = 7, defaultGraceSeconds = 99, defaultVibrateInGrace = false))

        assertEquals(EditorForm().copy(vibrateInGrace = false), form)
    }
}

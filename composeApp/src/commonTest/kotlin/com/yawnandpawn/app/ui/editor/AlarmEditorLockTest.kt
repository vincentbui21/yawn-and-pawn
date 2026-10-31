package com.yawnandpawn.app.ui.editor

import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.config.LockedField
import com.yawnandpawn.app.core.config.Occurrence
import com.yawnandpawn.app.core.config.PendingChange
import com.yawnandpawn.app.core.config.SettingValue
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeGlobalSettingsRepository
import com.yawnandpawn.app.testing.FakeIdGenerator
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMoneyFormatter
import com.yawnandpawn.app.testing.FakeNotificationPermission
import com.yawnandpawn.app.testing.FakePendingChangeRepository
import com.yawnandpawn.app.testing.FakePriceCatalog
import com.yawnandpawn.app.testing.FakeRequestCodeSequence
import com.yawnandpawn.app.testing.FakeSoundLibrary
import com.yawnandpawn.app.testing.FakeSoundPreview
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.aPriceSnapshot
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.checksetup.CheckSetupIntent
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.home.AlarmActions
import com.yawnandpawn.app.ui.settings.WeakeningNote
import com.yawnandpawn.app.ui.settings.weakeningNoteOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import com.yawnandpawn.app.core.checks.CheckMode as CoreCheckMode
import com.yawnandpawn.app.core.checks.CheckType as CoreCheckType
import com.yawnandpawn.app.core.checks.Difficulty as CoreDifficulty

/**
 * Story 4.6: the editor and the commitment lock (the quiet time is a Settings row since 0.1.7). A stored alarm opens with its
 * pending (weaker) values selected and the
 * "Saved. Takes effect after ..." notes, so saving again keeps the commitment (the Story 4.4 deferral); a weakening save
 * closes with the note for Home; and the Snooze sub-screen's fee ladder shows what the next ring will charge. Tests use
 * 23:40 and a 07:30 alarm (7 h 50 min), never F6's 23:10 (UX note).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmEditorLockTest {
    private val dispatcher = UnconfinedTestDispatcher()

    /** Monday 2027-03-08, 23:40 UTC. */
    private val lateEvening = Instant.parse("2027-03-08T23:40:00Z")
    private val sevenThirty = Instant.parse("2027-03-09T07:30:00Z")
    private val sevenFortyFive = Instant.parse("2027-03-09T07:45:00Z")
    private val clock = FakeClock(lateEvening - 12.hours)
    private val zone = FakeTimeZoneProvider(TimeZone.UTC)
    private val logger = FakeLogger()
    private val pending = FakePendingChangeRepository()
    private val settings = FakeGlobalSettingsRepository(GlobalSettings(baseFeeTier = 2, maxSnoozes = 5), pending)
    private val prices = FakePriceCatalog(aPriceSnapshot(currency = "EUR"))
    private val alarms =
        AlarmUseCasesFixture(
            repository = FakeAlarmRepository(),
            clock = clock,
            timeZoneProvider = zone,
            ids = FakeIdGenerator(),
            requestCodes = FakeRequestCodeSequence(lastUsed = RequestCodes.INITIAL_HIGH_WATER_MARK),
            scheduler = FakeAlarmScheduler(),
            logger = logger,
            pendingChanges = pending,
        )
    private val id = FakeIdGenerator.fakeUuid(1)

    /** Every day at 07:30, quiet time 20 s, Math at Medium (3 sums), saved at noon (no commitment then). */
    private val draft =
        AlarmDraft(
            time = LocalTime(7, 30),
            repeatDays = DayOfWeek.entries.toSet(),
            graceSeconds = 20,
            checks = listOf(CheckEntry(CoreCheckType.Math, CoreDifficulty.Medium, 3)),
        )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun storeAlarm() {
        assertTrue(alarms.save(draft) is Outcome.Success)
        clock.set(lateEvening)
    }

    private fun viewModel(
        alarmId: String? = id,
        copyOf: String? = null,
        withFees: Boolean = true,
    ) = AlarmEditorViewModel(
        alarmId = alarmId,
        repository = alarms.repository,
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
        pickable = CheckType.entries,
        pendingChanges = pending,
        fees = if (withFees) EditorFeeSources(settings, prices, FakeMoneyFormatter()) else null,
    )

    private fun TestScope.effectsOf(viewModel: AlarmEditorViewModel): List<EditorEffect> {
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
        return effects
    }

    private fun waitingGrace(seconds: Int) = PendingChange(id, SettingValue.GraceSeconds(seconds), Occurrence(id, sevenThirty))

    private fun ladderOf(viewModel: AlarmEditorViewModel): List<String>? =
        viewModel.state.value.full
            ?.feeLadder
            ?.map { (it as LadderPrice.Text).text }

    // Pending values and notes -------------------------------------------------------------------------------------

    /** Math at Easy (3 sums) in Random: easier than the stored Math at Medium, so it waits under the lock. */
    private val easier = CheckPlan(CoreCheckMode.Random, listOf(CheckEntry(CoreCheckType.Math, CoreDifficulty.Easy, 3)))

    private fun waitingEasier(at: Instant = sevenThirty) = PendingChange(id, SettingValue.Checks(easier), Occurrence(id, at))

    @Test
    fun `a leftover pending quiet time (before 0_1_7) opens the live value with no note, and saving clears it`() =
        runTest(dispatcher) {
            storeAlarm()
            pending.put(waitingGrace(30))
            val viewModel = viewModel()
            val effects = effectsOf(viewModel)
            val full = viewModel.state.value.full!!

            assertEquals(20, viewModel.state.value.form.graceSeconds, "the stored value, passed through unchanged")
            assertNull(full.weakeningNote)
            assertNull(full.checksNote)

            viewModel.onIntent(EditorIntent.LabelChanged("Gym"))
            viewModel.onIntent(EditorIntent.SaveClicked)

            assertEquals(emptyList(), pending.current)
            assertEquals(20, alarms.repository.get(id).let { (it as Outcome.Success).value.graceSeconds })
            assertEquals(listOf<EditorEffect>(EditorEffect.Close), effects)
        }

    @Test
    fun `pending easier checks moved to a later ring say and wait for that ring (review fix 1)`() =
        runTest(dispatcher) {
            storeAlarm()
            // 23:50: the moved ring at 07:45 is inside the window too (7 h 55 min).
            clock.set(Instant.parse("2027-03-08T23:50:00Z"))
            pending.put(waitingEasier())
            val viewModel = viewModel()
            val effects = effectsOf(viewModel)

            viewModel.onIntent(EditorIntent.TimeChanged(LocalTime(7, 45)))
            assertEquals(
                WeakeningNote(LocalTime(7, 45)),
                viewModel.state.value.full!!
                    .checksNote,
            )
            viewModel.onIntent(EditorIntent.SaveClicked)

            assertEquals(Occurrence(id, sevenFortyFive), pending.current.single().effectiveAfter)
            assertEquals(listOf<EditorEffect>(EditorEffect.CloseSavedUnderLock(WeakeningNote(LocalTime(7, 45)))), effects)
        }

    @Test
    fun `saving pending easier checks again with only a label change keeps the change as it is (review fix 3)`() =
        runTest(dispatcher) {
            storeAlarm()
            pending.put(waitingEasier())
            val before = pending.current
            val viewModel = viewModel()

            viewModel.onIntent(EditorIntent.LabelChanged("Gym"))
            viewModel.onIntent(EditorIntent.SaveClicked)

            assertEquals(before, pending.current)
        }

    @Test
    fun `a note saved at 23 59 before a ring at 00 30 says tomorrow's`() {
        val note = weakeningNoteOf(Instant.parse("2027-03-09T00:30:00Z"), Instant.parse("2027-03-08T23:59:00Z"), TimeZone.UTC)
        assertEquals(WeakeningNote(LocalTime(0, 30), today = false), note)
    }

    @Test
    fun `pending easier checks open selected with their note`() =
        runTest(dispatcher) {
            storeAlarm()
            pending.put(waitingEasier())
            val viewModel = viewModel()
            val state = viewModel.state.value

            assertEquals(listOf(CheckChip(CheckType.Math, Difficulty.Easy, 3)), state.form.checks)
            assertEquals(WeakeningNote(LocalTime(7, 30)), state.full!!.checksNote)
            assertEquals(WeakeningNote(LocalTime(7, 30)), state.full.weakeningNote)

            // Harder again: the note goes, as saving would apply the harder checks at once.
            viewModel.onIntent(EditorIntent.CheckSetupClicked(CheckType.Math))
            viewModel.onIntent(EditorIntent.CheckSetup(CheckSetupIntent.DifficultySelected(Difficulty.Hard)))
            assertNull(
                viewModel.state.value.full!!
                    .checksNote,
            )
        }

    @Test
    fun `a weakening save under the lock closes with the note for Home, today's after midnight`() =
        runTest(dispatcher) {
            storeAlarm()
            clock.set(Instant.parse("2027-03-09T01:00:00Z"))
            val viewModel = viewModel()
            val effects = effectsOf(viewModel)

            viewModel.onIntent(EditorIntent.CheckSetupClicked(CheckType.Math))
            viewModel.onIntent(EditorIntent.CheckSetup(CheckSetupIntent.DifficultySelected(Difficulty.Easy)))
            viewModel.onIntent(EditorIntent.SaveClicked)

            assertEquals(listOf<EditorEffect>(EditorEffect.CloseSavedUnderLock(WeakeningNote(LocalTime(7, 30), today = true))), effects)
            assertEquals(listOf(waitingEasier()), pending.current)
        }

    @Test
    fun `pending changes that cannot be read close the editor, so a save never cancels them unseen`() =
        runTest(dispatcher) {
            storeAlarm()
            pending.failure = DomainError.StorageFailure("corrupt")
            val viewModel = viewModel()
            val effects = effectsOf(viewModel)

            assertEquals(listOf<EditorEffect>(EditorEffect.OpenFailed), effects)
        }

    @Test
    fun `a duplicate opens with the live values and no note`() =
        runTest(dispatcher) {
            storeAlarm()
            pending.put(waitingEasier())
            val viewModel = viewModel(alarmId = null, copyOf = id)

            assertEquals(listOf(CheckChip(CheckType.Math, Difficulty.Medium, 3)), viewModel.state.value.form.checks)
            assertNull(
                viewModel.state.value.full!!
                    .checksNote,
            )
        }

    // Fee ladder ---------------------------------------------------------------------------------------------------

    // Story 7.11's skip in the editor's header (0.1.8: "Offer to skip 2 h before" is the global Skip reminder now) ----

    @Test
    fun `the header's Rings in passes over a skipped next ring`() =
        runTest(dispatcher) {
            assertTrue(alarms.save(draft) is Outcome.Success)
            clock.set(lateEvening)
            assertTrue(alarms.skip(id) is Outcome.Success)

            // 23:40 Monday: Tuesday's 07:30 is skipped, so Wednesday's is in 31 h 50 min.
            assertEquals(Countdown.DaysHours(1, 7), viewModel().state.value.ringsIn)
            // A duplicate is a new alarm: no skip.
            assertEquals(Countdown.HoursMinutes(7, 50), viewModel(alarmId = null, copyOf = id).state.value.ringsIn)
        }

    @Test
    fun `the Snooze sub-screen shows the fee ladder in Play's prices`() =
        runTest(dispatcher) {
            storeAlarm()
            val viewModel = viewModel()

            assertEquals(listOf("EUR 2.00", "EUR 4.00", "EUR 6.00"), ladderOf(viewModel))
            assertFalse(
                viewModel.state.value.full!!
                    .pricesApproximate,
            )
        }

    @Test
    fun `with no cached prices the ladder is approximate USD`() =
        runTest(dispatcher) {
            storeAlarm()
            prices.snapshot = PriceCatalogSnapshot.EMPTY
            val viewModel = viewModel()

            assertEquals(listOf("USD 2.000000", "USD 4.000000", "USD 6.000000"), ladderOf(viewModel))
            assertTrue(
                viewModel.state.value.full!!
                    .pricesApproximate,
            )

            // Back online: the ladder follows the cache.
            prices.snapshot = aPriceSnapshot(currency = "EUR")
            assertEquals(listOf("EUR 2.00", "EUR 4.00", "EUR 6.00"), ladderOf(viewModel))
            assertFalse(
                viewModel.state.value.full!!
                    .pricesApproximate,
            )
        }

    @Test
    fun `with max snoozes 2 the ladder has two entries`() =
        runTest(dispatcher) {
            storeAlarm()
            settings.setMaxSnoozes(2)
            val viewModel = viewModel()

            assertEquals(listOf("EUR 2.00", "EUR 4.00"), ladderOf(viewModel))
        }

    @Test
    fun `a lower fee waiting for this alarm's ring is not shown, the next morning still charges the live fee`() =
        runTest(dispatcher) {
            storeAlarm()
            pending.put(PendingChange(null, SettingValue.BaseFeeTier(1), Occurrence(id, sevenThirty)))
            val viewModel = viewModel()

            assertEquals(listOf("EUR 2.00", "EUR 4.00", "EUR 6.00"), ladderOf(viewModel))
        }

    @Test
    fun `a lower fee that waited for an earlier ring applies to this alarm's next ring`() =
        runTest(dispatcher) {
            storeAlarm()
            val earlier = Occurrence("other", Instant.parse("2027-03-09T06:00:00Z"))
            pending.put(PendingChange(null, SettingValue.BaseFeeTier(1), earlier))
            val viewModel = viewModel()

            assertEquals(listOf("EUR 1.00", "EUR 2.00", "EUR 3.00"), ladderOf(viewModel))
        }

    @Test
    fun `a lower fee waiting for this alarm is still not shown after moving the alarm later (review fix 2)`() =
        runTest(dispatcher) {
            storeAlarm()
            pending.put(PendingChange(null, SettingValue.BaseFeeTier(1), Occurrence(id, sevenThirty)))
            val viewModel = viewModel()

            viewModel.onIntent(EditorIntent.TimeChanged(LocalTime(7, 45)))

            assertEquals(listOf("EUR 2.00", "EUR 4.00", "EUR 6.00"), ladderOf(viewModel))
        }

    @Test
    fun `the ladder follows the form's time, since a pending fee depends on which ring is next`() =
        runTest(dispatcher) {
            storeAlarm()
            val earlier = Occurrence("other", Instant.parse("2027-03-09T06:00:00Z"))
            pending.put(PendingChange(null, SettingValue.BaseFeeTier(1), earlier))
            val viewModel = viewModel()

            viewModel.onIntent(EditorIntent.TimeChanged(LocalTime(5, 30)))

            assertEquals(listOf("EUR 2.00", "EUR 4.00", "EUR 6.00"), ladderOf(viewModel))
        }

    @Test
    fun `a new alarm shows the ladder too, and an editor without fee sources shows none`() =
        runTest(dispatcher) {
            assertEquals(listOf("EUR 2.00", "EUR 4.00", "EUR 6.00"), ladderOf(viewModel(alarmId = null)))
            assertNull(ladderOf(viewModel(alarmId = null, withFees = false)))
        }
}

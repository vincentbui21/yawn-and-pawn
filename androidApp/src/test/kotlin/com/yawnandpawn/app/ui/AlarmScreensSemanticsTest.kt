package com.yawnandpawn.app.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.MissedNotes
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.core.sound.ImportResult
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeCheckConfigRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeCustomSoundRepository
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMissedNoteDismissals
import com.yawnandpawn.app.testing.FakeNotificationPermission
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeSoundImporter
import com.yawnandpawn.app.testing.FakeSoundLibrary
import com.yawnandpawn.app.testing.FakeSoundPreview
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.FakeTimeChangeSignal
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.testing.anEvaluateReliability
import com.yawnandpawn.app.testing.noReRegisterSuggestions
import com.yawnandpawn.app.ui.editor.AlarmEditorRoute
import com.yawnandpawn.app.ui.editor.AlarmEditorScreen
import com.yawnandpawn.app.ui.editor.AlarmEditorViewModel
import com.yawnandpawn.app.ui.editor.EditorIntent
import com.yawnandpawn.app.ui.editor.EditorPane
import com.yawnandpawn.app.ui.editor.EditorSoundFiles
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.editor.RingsAt
import com.yawnandpawn.app.ui.format.DayNameStyle
import com.yawnandpawn.app.ui.format.WeekOrder
import com.yawnandpawn.app.ui.format.dayName
import com.yawnandpawn.app.ui.home.AlarmActions
import com.yawnandpawn.app.ui.home.HomeIntent
import com.yawnandpawn.app.ui.home.HomeRoute
import com.yawnandpawn.app.ui.home.HomeScreen
import com.yawnandpawn.app.ui.home.HomeUiState
import com.yawnandpawn.app.ui.home.HomeViewModel
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.sound.SoundFilePicker
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Accessibility floor for the Story 1.8 and 1.9 screens (editor, Home): every touch target is at least 48 dp, and
 * every control TalkBack can act on has a label, a role (or is a text field or slider) and, where it has one, its state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1400dp-mdpi")
class AlarmScreensSemanticsTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun editor(
        state: EditorUiState,
        is24Hour: Boolean = false,
        block: () -> Unit,
    ) = withScreen(PpsThemeMode.Light, content = { AlarmEditorScreen(state = state, is24Hour = is24Hour, onIntent = {}) }, block = block)

    /** Controls TalkBack can act on: tappable, adjustable (slider) or editable. */
    private val actionable =
        hasClickAction() or SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) or hasSetTextAction()

    private fun ComposeTestRule.actionableNodes(): List<SemanticsNode> = onAllNodes(actionable).fetchSemanticsNodes()

    private fun SemanticsNode.describe(): String = "${config.getOrNull(SemanticsProperties.Role)} '${label()}' ($config)"

    private fun SemanticsNode.label(): String =
        listOfNotNull(
            config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(),
            config.getOrNull(SemanticsProperties.Text)?.joinToString(),
            config.getOrNull(SemanticsProperties.EditableText)?.text,
        ).joinToString(" ").trim()

    /** Touch bounds include Material's minimum interactive size, as touch and TalkBack see them. */
    private fun assertMinTouchTarget(node: SemanticsNode) {
        val bounds = node.touchBoundsInRoot
        with(node.layoutInfo.density) {
            val ok = bounds.width.toDp() >= 48.dp && bounds.height.toDp() >= 48.dp
            assertTrue(ok, "target ${bounds.width.toDp()} x ${bounds.height.toDp()} is under 48 dp: ${node.describe()}")
        }
    }

    private fun assertAccessibilityFloor(minControls: Int = 6) {
        val nodes = composeRule.actionableNodes()
        assertTrue(nodes.size >= minControls, "found only ${nodes.size} controls")
        nodes.forEach { node ->
            assertMinTouchTarget(node)
            assertTrue(node.label().isNotEmpty(), "no TalkBack label: ${node.describe()}")
            val config = node.config
            val role = config.getOrNull(SemanticsProperties.Role)
            val isTextField = SemanticsActions.SetText in config
            val isSlider = SemanticsProperties.ProgressBarRangeInfo in config
            assertTrue(role != null || isTextField || isSlider, "no role: ${node.describe()}")
            when (role) {
                Role.Switch, Role.Checkbox -> {
                    assertTrue(
                        SemanticsProperties.ToggleableState in config,
                        "no on/off state: ${node.describe()}",
                    )
                }

                Role.RadioButton, Role.Tab -> {
                    assertTrue(SemanticsProperties.Selected in config, "no selected state: ${node.describe()}")
                }

                else -> {
                    Unit
                }
            }
            if (isSlider) assertTrue(SemanticsProperties.StateDescription in config, "slider value not announced: ${node.describe()}")
        }
    }

    @Test
    fun `new alarm editor meets the accessibility floor`() =
        editor(EditorSamples.newAlarm) {
            assertAccessibilityFloor()
        }

    @Test
    fun `edit alarm editor in 24-hour mode meets the accessibility floor`() =
        editor(EditorSamples.editAlarm, is24Hour = true) {
            assertAccessibilityFloor()
        }

    @Test
    @Config(qualifiers = "+h2400dp", fontScale = 2.0f)
    fun `the editor at 200 percent font scale meets the accessibility floor`() =
        editor(EditorSamples.newAlarm) {
            assertAccessibilityFloor()
        }

    @Test
    fun `the discard dialog actions meet the accessibility floor`() =
        editor(EditorSamples.discardDialog) {
            composeRule.onNode(isDialog()).assertExists()
            composeRule.onNodeWithText("Keep editing").assert(hasClickAction())
            composeRule.onNodeWithText("Discard").assert(hasClickAction())
            assertAccessibilityFloor()
        }

    @Test
    fun `day chips read full day names with their checked state`() =
        editor(EditorSamples.editAlarm) {
            composeRule.onNodeWithContentDescription("Monday").assertIsOn()
            composeRule.onNodeWithContentDescription("Tuesday").assertIsOff()
            composeRule.onNodeWithContentDescription("Sunday").assertIsOff()
            composeRule.onNodeWithText("M").assertDoesNotExist()
        }

    @Test
    fun `the Sound and Snooze sub-screens meet the accessibility floor`() {
        editor(EditorSamples.soundPane) { assertAccessibilityFloor(minControls = 3) }
        editor(EditorSamples.snoozePane) { assertAccessibilityFloor(minControls = 5) }
    }

    @Test
    fun `snooze lengths are radio buttons with 10 min selected by default`() =
        editor(EditorSamples.snoozePane) {
            composeRule.onNode(hasText("10 min") and hasClickAction()).assertIsSelected()
            composeRule
                .onNode(hasText("5 min") and hasClickAction())
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        }

    @Test
    fun `the volume slider is labelled and announces its value, and there is no starting-volume slider`() =
        editor(EditorSamples.soundPane) {
            composeRule
                .onNode(
                    hasContentDescription("Volume"),
                ).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "80%"))
            composeRule.onNode(hasContentDescription("Starting volume")).assertDoesNotExist()
            composeRule.onNodeWithText("Starting volume").assertDoesNotExist()
        }

    @Test
    fun `switches read their label with the switch role and state`() {
        editor(EditorSamples.soundGradualOff) {
            composeRule.onNode(hasText("Gradually increase volume") and hasClickAction()).assertIsOff()
            composeRule.onNode(hasContentDescription("Starting volume")).assertDoesNotExist()
        }
        editor(EditorSamples.newAlarm) { composeRule.onNode(hasText("Vibration") and hasClickAction()).assertIsOn() }
    }

    @Test
    fun `the pill has Cancel and Save, and a sub-screen's back arrow is labelled Back`() {
        editor(EditorSamples.editAlarm) {
            composeRule.onNodeWithText("Cancel").assert(hasClickAction())
            composeRule.onNodeWithText("Save").assert(hasClickAction())
            composeRule.onNodeWithText("Edit alarm").assertExists()
        }
        editor(EditorSamples.snoozePane) {
            composeRule.onNodeWithContentDescription("Back").assert(hasClickAction())
        }
    }

    @Test
    @Config(qualifiers = "+w360dp")
    fun `on a 360 dp screen the day chips stay on one line with 48 dp targets`() =
        editor(EditorSamples.editAlarm) {
            assertAccessibilityFloor()
            val tops =
                WeekOrder.map { day ->
                    composeRule
                        .onNodeWithContentDescription(dayName(day, DayNameStyle.Full))
                        .fetchSemanticsNode()
                        .boundsInRoot.top
                }
            assertEquals(1, tops.distinct().size, "chips on more than one line: $tops")
        }

    @Test
    fun `a label error shows its text and puts the field in error state`() =
        editor(EditorSamples.labelError) {
            composeRule.onNodeWithText("Keep the label under 40 characters.", useUnmergedTree = true).assertExists()
            composeRule
                .onNode(hasSetTextAction() and hasText("Alarm name"))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Keep the label under 40 characters."))
            composeRule
                .onNode(
                    hasText("Keep the label under 40 characters.") and
                        SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
                    useUnmergedTree = true,
                ).assertExists()
        }

    @Test
    fun `the time card says when the alarm rings, and says nothing before that is known (0_1_7 polish)`() {
        val lines =
            mapOf(
                RingsAt(0, DayOfWeek.WEDNESDAY, LocalTime(7, 0)) to "Rings today at 7:00 AM.",
                RingsAt(1, DayOfWeek.THURSDAY, LocalTime(7, 0)) to "Rings tomorrow at 7:00 AM.",
                RingsAt(5, DayOfWeek.MONDAY, LocalTime(7, 0)) to "Rings on Monday at 7:00 AM.",
                RingsAt(7, DayOfWeek.WEDNESDAY, LocalTime(7, 0)) to "Rings next Wednesday at 7:00 AM.",
            )
        lines.forEach { (ringsAt, line) ->
            editor(EditorUiState(ringsAt = ringsAt)) {
                composeRule.onNodeWithText(line, useUnmergedTree = true).assertExists()
            }
        }
        editor(EditorUiState(ringsAt = null)) {
            val rings = composeRule.onAllNodes(hasText("Rings", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals(0, rings.size, "the reserved line is silent")
        }
    }

    @Test
    fun `a failed save shows the snackbar and the editor stays open`() {
        val repository = FakeAlarmRepository().apply { failure = DomainError.StorageFailure("disk I/O error") }
        val viewModel =
            AlarmEditorViewModel(
                alarmId = null,
                repository = repository,
                checkConfigs = FakeCheckConfigRepository(repository),
                saveAlarm = AlarmUseCasesFixture(repository = repository).save,
                clock = FakeClock(),
                timeZoneProvider = FakeTimeZoneProvider(),
                actions = actions(repository),
                soundLibrary = FakeSoundLibrary(),
                soundPreview = FakeSoundPreview(),
                notificationPermission = FakeNotificationPermission(),
                testAlarm = ScheduleTestAlarm(FakeAlarmScheduler(), FakeTestAlarmStore(), FakeClock(), FakeLogger()),
            )
        var closed = false
        withScreen(
            PpsThemeMode.Light,
            content = {
                AlarmEditorRoute(
                    alarmId = null,
                    onClose = { closed = true },
                    onOpenFailed = { closed = true },
                    onOpenCopy = {},
                    viewModel = viewModel,
                )
            },
        ) {
            composeRule.onNodeWithText("Save").performClick()

            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(hasText("Couldn't save the alarm. Try again.")).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("New alarm").assertExists()
            assertFalse(closed)
            assertTrue(repository.current.isEmpty())
        }
    }

    @Test
    fun `a picked file that is too big shows its snackbar in the Sound sub-screen, and the choice stays (Story 7_4)`() {
        val repository = FakeAlarmRepository()
        val importer = FakeSoundImporter(Outcome.Success(ImportResult.TooBig))
        val picker =
            object : SoundFilePicker {
                override suspend fun pick(): String = "content://picked/1"
            }
        val viewModel =
            AlarmEditorViewModel(
                alarmId = null,
                repository = repository,
                checkConfigs = FakeCheckConfigRepository(repository),
                saveAlarm = AlarmUseCasesFixture(repository = repository).save,
                clock = FakeClock(),
                timeZoneProvider = FakeTimeZoneProvider(),
                actions = actions(repository),
                soundLibrary = FakeSoundLibrary(),
                soundPreview = FakeSoundPreview(),
                notificationPermission = FakeNotificationPermission(),
                testAlarm = ScheduleTestAlarm(FakeAlarmScheduler(), FakeTestAlarmStore(), FakeClock(), FakeLogger()),
                soundFiles = EditorSoundFiles(FakeCustomSoundRepository(), picker, importer),
            )
        viewModel.onIntent(EditorIntent.PaneOpened(EditorPane.Sound))
        withScreen(
            PpsThemeMode.Light,
            content = { AlarmEditorRoute(alarmId = null, onClose = {}, onOpenFailed = {}, onOpenCopy = {}, viewModel = viewModel) },
        ) {
            composeRule.onNode(hasText("Pick a file") and hasClickAction()).performScrollTo().performClick()

            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(hasText("This file is too big. Pick one under 20 MB.")).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(0, composeRule.onAllNodes(hasText("This file can't be played. Pick another.")).fetchSemanticsNodes().size)
            assertEquals(listOf("content://picked/1"), importer.imported)
            assertEquals(Alarm.DEFAULT_SOUND_REF, viewModel.state.value.form.soundRef)
        }
    }

    @Test
    fun `More options then Test alarm arms a test ring and shows the snackbar, and the editor stays open (0_1_8)`() {
        val repository = FakeAlarmRepository()
        val testScheduler = FakeAlarmScheduler()
        val testAlarms = FakeTestAlarmStore()
        val viewModel =
            AlarmEditorViewModel(
                alarmId = null,
                repository = repository,
                checkConfigs = FakeCheckConfigRepository(repository),
                saveAlarm = AlarmUseCasesFixture(repository = repository).save,
                clock = FakeClock(),
                timeZoneProvider = FakeTimeZoneProvider(),
                actions = actions(repository),
                soundLibrary = FakeSoundLibrary(),
                soundPreview = FakeSoundPreview(),
                notificationPermission = FakeNotificationPermission(),
                testAlarm = ScheduleTestAlarm(testScheduler, testAlarms, FakeClock(), FakeLogger()),
            )
        withScreen(
            PpsThemeMode.Light,
            content = { AlarmEditorRoute(alarmId = null, onClose = {}, onOpenFailed = {}, onOpenCopy = {}, viewModel = viewModel) },
        ) {
            // 0.1.8: "Test alarm" left the editor body for the header's "More options" menu.
            composeRule.onNodeWithText("Test alarm").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("More options").performClick()
            composeRule.onNode(hasText("Test alarm") and hasClickAction()).performClick()

            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(hasText("Lock your phone. We'll ring in 10 seconds.")).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("New alarm").assertExists()
            assertTrue(testAlarms.pending?.testMode == true, "a test config is pending")
            assertEquals(setOf(RequestCodes.TEST_ALARM), testScheduler.armed.keys)
            assertTrue(repository.current.isEmpty(), "nothing saved")
        }
    }

    private fun actions(repository: FakeAlarmRepository): AlarmActions {
        val alarms = AlarmUseCasesFixture(repository = repository)
        return AlarmActions(alarms.setEnabled, alarms.delete, FakeLogger())
    }

    @Test
    fun `an alarm that cannot be read closes the editor so Home can say so`() {
        val repository = FakeAlarmRepository()
        val viewModel =
            AlarmEditorViewModel(
                alarmId = "missing",
                repository = repository,
                checkConfigs = FakeCheckConfigRepository(repository),
                saveAlarm = AlarmUseCasesFixture(repository = repository).save,
                clock = FakeClock(),
                timeZoneProvider = FakeTimeZoneProvider(),
                actions = actions(repository),
                soundLibrary = FakeSoundLibrary(),
                soundPreview = FakeSoundPreview(),
                notificationPermission = FakeNotificationPermission(),
                testAlarm = ScheduleTestAlarm(FakeAlarmScheduler(), FakeTestAlarmStore(), FakeClock(), FakeLogger()),
            )
        var openFailed = false
        var closed = false
        withScreen(
            PpsThemeMode.Light,
            content = {
                AlarmEditorRoute(
                    alarmId = "missing",
                    onClose = { closed = true },
                    onOpenFailed = { openFailed = true },
                    onOpenCopy = {},
                    viewModel = viewModel,
                )
            },
        ) {
            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) { openFailed }
            assertFalse(closed)
        }
    }

    // Home (Story 1.9) ----------------------------------------------------------------------------------------------

    private fun home(
        state: HomeUiState,
        onIntent: (HomeIntent) -> Unit = {},
        block: () -> Unit,
    ) = withScreen(
        PpsThemeMode.Light,
        content = {
            AppShell(
                selected = AppTab.Alarms,
                onSelect = {},
            ) { HomeScreen(state = state, is24Hour = false, onIntent = onIntent) }
        },
        block = block,
    )

    /** The `card-alarm` tap target of the alarm at [time]. */
    private fun card(time: String) = composeRule.onNode(hasText(time) and hasClickAction())

    @Test
    fun `the Home empty state and the nav bar meet the accessibility floor`() =
        home(HomeSamples.empty) {
            composeRule.onNodeWithText("No alarms yet.").assertExists()
            composeRule.onNodeWithText("Add your first alarm").assert(hasClickAction())
            composeRule.onNodeWithContentDescription("Add alarm").assert(hasClickAction())
            assertAccessibilityFloor(minControls = 6)
        }

    @Test
    fun `the Home list meets the accessibility floor`() =
        home(HomeSamples.many) {
            assertAccessibilityFloor(minControls = 12)
        }

    @Test
    @Config(fontScale = 2.0f)
    fun `the Home list at 200 percent font scale meets the accessibility floor`() =
        home(HomeSamples.many) {
            assertAccessibilityFloor(minControls = 8)
        }

    @Test
    fun `an alarm card reads time and summary as one button, with its switch on its own`() =
        home(HomeSamples.many) {
            card("6:30 AM").assert(hasText("Mon, Wed, Fri"))
            card("7:15 AM").assert(hasText("Once · Stand-up"))
            card("9:00 AM").assert(hasText("Every day"))
            composeRule.onNodeWithContentDescription("8:00 AM alarm").assertIsOff()
            composeRule.onNodeWithContentDescription("9:00 AM alarm").assertIsOn()
        }

    @Test
    fun `a card has a long-press label and offers Duplicate and Delete to TalkBack without long-press`() {
        val intents = mutableListOf<HomeIntent>()
        home(HomeSamples.many, onIntent = { intents += it }) {
            val config = card("6:30 AM").fetchSemanticsNode().config
            assertEquals("Duplicate or delete", config[SemanticsActions.OnLongClick].label)
            val actions = config[SemanticsActions.CustomActions]
            assertEquals(listOf("Duplicate", "Delete"), actions.map { it.label })

            actions.forEach { it.action() }

            assertEquals(listOf<HomeIntent>(HomeIntent.DuplicateClicked("2"), HomeIntent.DeleteClicked("2")), intents)
        }
    }

    @Test
    fun `long-press opens the menu with Duplicate and Delete as 48 dp items`() {
        val intents = mutableListOf<HomeIntent>()
        home(HomeSamples.many, onIntent = { intents += it }) {
            card("6:30 AM").performTouchInput { longClick() }

            listOf("Duplicate", "Delete").forEach { label ->
                val item = composeRule.onNode(hasText(label) and hasClickAction())
                item.assertExists()
                assertMinTouchTarget(item.fetchSemanticsNode())
            }
            composeRule.onNode(hasText("Duplicate") and hasClickAction()).performClick()

            assertEquals(listOf<HomeIntent>(HomeIntent.DuplicateClicked("2")), intents)
            composeRule.onNode(hasText("Delete") and hasClickAction()).assertDoesNotExist()
        }
    }

    @Test
    fun `the delete dialog says it is logged, with Delete and the safe Keep it`() {
        val intents = mutableListOf<HomeIntent>()
        home(HomeSamples.deleteDialog, onIntent = { intents += it }) {
            composeRule.onNode(isDialog()).assertExists()
            composeRule.onNodeWithText("Delete the 6:30 AM alarm?").assertExists()
            composeRule.onNodeWithText("Keep it").performClick()
            composeRule.onNodeWithText("Delete").performClick()

            assertEquals(listOf<HomeIntent>(HomeIntent.DeleteCancelled, HomeIntent.DeleteConfirmed), intents)
        }
    }

    @Test
    fun `a load failure shows Try again and neither the empty state nor a list`() {
        val intents = mutableListOf<HomeIntent>()
        home(HomeSamples.loadFailed, onIntent = { intents += it }) {
            composeRule.onNodeWithText("Couldn't load your alarms.").assertExists()
            val retry = composeRule.onNode(hasText("Try again") and hasClickAction())
            assertMinTouchTarget(retry.fetchSemanticsNode())
            composeRule.onNodeWithText("No alarms yet.").assertDoesNotExist()
            composeRule.onNodeWithText("Add your first alarm").assertDoesNotExist()

            retry.performClick()

            assertEquals(listOf<HomeIntent>(HomeIntent.RetryLoad), intents)
        }
    }

    @Test
    fun `the re-register banner sits below the reliability banner, and Re-register and Dismiss are 48 dp targets`() {
        val intents = mutableListOf<HomeIntent>()
        home(HomeSamples.reRegisterUnderReliability, onIntent = { intents += it }) {
            val reliability = composeRule.onNodeWithText("Alarms may not ring. Fix settings").fetchSemanticsNode()
            val reRegister =
                composeRule.onNodeWithText("Fallback check used 3 times this week. Re-register your QR/Barcode?").fetchSemanticsNode()
            assertTrue(reliability.boundsInRoot.bottom <= reRegister.boundsInRoot.top, "the reliability banner stays above")
            val action = composeRule.onNode(hasText("Re-register") and hasClickAction())
            val close = composeRule.onNode(hasContentDescription("Dismiss") and hasClickAction())
            listOf(action, close).forEach { assertMinTouchTarget(it.fetchSemanticsNode()) }

            action.performClick()
            close.performClick()

            assertEquals(listOf(HomeIntent.ReregisterClicked, HomeIntent.ReregisterDismissed), intents)
        }
    }

    @Test
    @Config(fontScale = 2.0f)
    fun `Home with both banners at 200 percent font scale meets the accessibility floor`() =
        home(HomeSamples.reRegisterUnderReliability) {
            composeRule.onNodeWithText("Fallback check used 3 times this week. Re-register your QR/Barcode?").assertExists()
            assertAccessibilityFloor(minControls = 8)
        }

    @Test
    fun `while the alarms load Home shows neither the empty state nor a list`() =
        home(HomeSamples.loading) {
            composeRule.onNodeWithText("Yawn & Pawn").assertExists()
            composeRule.onNodeWithText("No alarms yet.").assertDoesNotExist()
            composeRule.onNodeWithText("Couldn't load your alarms.").assertDoesNotExist()
            composeRule.onNode(hasText("AM", substring = true) and hasClickAction()).assertDoesNotExist()
        }

    @Test
    fun `an editor that could not open its alarm leaves the snackbar on Home`() =
        home(HomeSamples.openFailed) {
            composeRule.onNodeWithText("Couldn't open this alarm.").assertExists()
        }

    @Test
    fun `a change that could not be saved leaves Couldn't save on Home`() =
        home(HomeSamples.saveFailed) {
            composeRule.onNodeWithText("Couldn't save the alarm. Try again.").assertExists()
            composeRule.onNodeWithText("Couldn't open this alarm.").assertDoesNotExist()
        }

    @Test
    fun `a switch that cannot be saved goes back and Home says Couldn't save`() {
        val repository = FakeAlarmRepository(listOf(anAlarm(time = LocalTime(6, 30))))
        val viewModel =
            HomeViewModel(
                FakeCheckConfigRepository(repository),
                actions(repository),
                // Noon: the 6:30 ring is outside the 8-hour lock window, so the switch turns off at once (Story 4.6).
                FakeClock(Instant.parse("2027-03-03T12:00:00Z")),
                FakeTimeZoneProvider(TimeZone.UTC),
                FakeTimeChangeSignal(),
                MissedNotes(FakeSessionHistoryRepository(), FakeMissedNoteDismissals()),
                anEvaluateReliability(checks = FakeCheckConfigRepository(repository)),
                noReRegisterSuggestions(),
            )
        withScreen(
            PpsThemeMode.Light,
            content = {
                HomeRoute(
                    onOpenEditor = {},
                    onOpenDuplicate = {},
                    openFailed = false,
                    onOpenFailedShown = {},
                    viewModel = viewModel,
                )
            },
        ) {
            val switch = composeRule.onNodeWithContentDescription("6:30 AM alarm")
            switch.assertIsOn()
            repository.failure = DomainError.StorageFailure("disk full")

            switch.performClick()

            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(hasText("Couldn't save the alarm. Try again.")).fetchSemanticsNodes().isNotEmpty()
            }
            switch.assertIsOn()
        }
    }

    @Test
    fun `a stored alarm's More options menu has Test alarm, Duplicate and Delete, a new alarm's Test alarm only (0_1_8)`() {
        val intents = mutableListOf<EditorIntent>()
        withScreen(
            PpsThemeMode.Light,
            content = { AlarmEditorScreen(state = EditorSamples.editStored, is24Hour = false, onIntent = { intents += it }) },
        ) {
            assertAccessibilityFloor()
            composeRule.onNodeWithContentDescription("More options").performClick()
            assertEquals(listOf("Test alarm", "Duplicate", "Delete"), menuItems())
            composeRule.onNode(hasText("Delete") and hasClickAction()).performClick()

            assertEquals(listOf<EditorIntent>(EditorIntent.DeleteClicked), intents)
        }
        // A new alarm, and an alarm not stored yet (a duplicate): the button is there, with "Test alarm" alone.
        listOf(EditorSamples.newAlarm, EditorSamples.editAlarm).forEach { state ->
            intents.clear()
            withScreen(
                PpsThemeMode.Light,
                content = { AlarmEditorScreen(state = state, is24Hour = false, onIntent = { intents += it }) },
            ) {
                composeRule.onNodeWithContentDescription("More options").performClick()
                assertEquals(listOf("Test alarm"), menuItems())
                composeRule.onNode(hasText("Test alarm") and hasClickAction()).performClick()

                assertEquals(listOf<EditorIntent>(EditorIntent.TestAlarmClicked), intents)
            }
        }
    }

    /** The open overflow menu's items, top to bottom, as TalkBack reads them. */
    private fun menuItems(): List<String> =
        composeRule
            .onAllNodes(hasAnyAncestor(isPopup()) and hasClickAction())
            .fetchSemanticsNodes()
            .map { it.label() }

    @Test
    fun `the editor's delete dialog meets the accessibility floor`() =
        editor(EditorSamples.editDeleteDialog) {
            composeRule.onNode(isDialog()).assertExists()
            composeRule.onNodeWithText("Delete the 6:30 AM alarm?").assertExists()
            composeRule.onNodeWithText("Keep it").assert(hasClickAction())
            assertAccessibilityFloor(minControls = 2)
        }

    @Test
    fun `an editor open failure passed to the Home route shows the snackbar and is reported as shown`() {
        val repository = FakeAlarmRepository()
        val missedNotes = MissedNotes(FakeSessionHistoryRepository(), FakeMissedNoteDismissals())
        val viewModel =
            HomeViewModel(
                FakeCheckConfigRepository(repository),
                actions(repository),
                FakeClock(),
                FakeTimeZoneProvider(),
                FakeTimeChangeSignal(),
                missedNotes,
                anEvaluateReliability(checks = FakeCheckConfigRepository(repository)),
                noReRegisterSuggestions(),
            )
        var shown = false
        withScreen(
            PpsThemeMode.Light,
            content = {
                HomeRoute(
                    onOpenEditor = {},
                    onOpenDuplicate = {},
                    openFailed = true,
                    onOpenFailedShown = { shown = true },
                    viewModel = viewModel,
                )
            },
        ) {
            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(hasText("Couldn't open this alarm.")).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(shown)
        }
    }
}

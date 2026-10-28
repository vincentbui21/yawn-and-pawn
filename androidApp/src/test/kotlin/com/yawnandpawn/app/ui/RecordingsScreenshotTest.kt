package com.yawnandpawn.app.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.editor.AlarmEditorScreen
import com.yawnandpawn.app.ui.editor.EditorIntent
import com.yawnandpawn.app.ui.editor.EditorPane
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.editor.FullEditorSections
import com.yawnandpawn.app.ui.editor.MotivationChoice
import com.yawnandpawn.app.ui.editor.MotivationRecording
import com.yawnandpawn.app.ui.recordings.RecordedMessage
import com.yawnandpawn.app.ui.recordings.RecorderState
import com.yawnandpawn.app.ui.recordings.RecordingsIntent
import com.yawnandpawn.app.ui.recordings.RecordingsScreen
import com.yawnandpawn.app.ui.recordings.RecordingsUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Recordings and Motivation states of Story 7.2, as the app builds them. */
object RecordingsSamples {
    private val levels = listOf(0.2f, 0.5f, 0.8f, 0.6f, 0.3f, 0.7f, 1f, 0.8f, 0.4f, 0.2f, 0.5f, 0.9f)

    val empty = RecordingsUiState()

    val recording = RecordingsUiState(recorder = RecorderState.Recording(seconds = 12, levels = levels))

    val take = RecordingsUiState(recorder = RecorderState.Recorded(seconds = 9))

    /** Two messages, the second restored without its file. */
    val listWithMissing =
        RecordingsUiState(
            messages =
                listOf(
                    RecordedMessage(id = "r1", name = "Message 1", seconds = 9),
                    RecordedMessage(id = "r2", name = "Message 2", seconds = 14, missing = true),
                ),
        )

    val micDenied = RecordingsUiState(micDenied = true, messages = listWithMissing.messages.take(1))

    private val app =
        FullEditorSections(
            rows = setOf(EditorPane.WakeCheck, EditorPane.Motivation),
            recordings = listOf(MotivationRecording("r1", "Message 1"), MotivationRecording("r2", "Message 2")),
            motivationTimingShown = false,
        )

    /** Two messages, Random chosen. */
    val motivationRandom = EditorUiState(pane = EditorPane.Motivation, full = app.copy(motivation = MotivationChoice.Random))

    /** The chosen message's file is missing. */
    val motivationMissing =
        EditorUiState(
            pane = EditorPane.Motivation,
            full =
                app.copy(
                    recordings = listOf(MotivationRecording("r1", "Message 1"), MotivationRecording("r2", "Message 2", missing = true)),
                    motivation = MotivationChoice.Recording("r2"),
                ),
        )

    /** One message: no "Random" to offer. */
    val motivationOne =
        EditorUiState(pane = EditorPane.Motivation, full = app.copy(recordings = listOf(MotivationRecording("r1", "Message 1"))))

    /** The editor's main screen with "Message 1" chosen. */
    val editorChosen = EditorUiState(full = app.copy(motivation = MotivationChoice.Recording("r1")))
}

/**
 * Story 7.2 screenshots of Recordings (empty, recording, after a stop, a list with a missing file, the microphone off)
 * and of the editor's Motivation sub-screen (Random chosen, a missing file), in Light, Dark and Light at 200% font
 * scale, plus semantics: targets of at least 48 dp, the 10 s time announcement and the Random rule.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h900dp-mdpi")
class RecordingsScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun capture(
        name: String,
        state: RecordingsUiState,
        mode: PpsThemeMode,
        checks: () -> Unit = {},
    ) = withScreen(mode, content = { RecordingsScreen(state = state, onIntent = {}) }) {
        checks()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    private fun captureEditor(
        name: String,
        state: EditorUiState,
        mode: PpsThemeMode,
        checks: () -> Unit = {},
    ) = withScreen(mode, content = { AlarmEditorScreen(state = state, is24Hour = false, onIntent = {}) }) {
        checks()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    private fun lightAndDark(
        name: String,
        state: RecordingsUiState,
    ) = listOf(PpsThemeMode.Light to "light", PpsThemeMode.Dark to "dark").forEach { (mode, suffix) ->
        capture("${name}_$suffix", state, mode)
    }

    @Test
    fun `empty in Light and Dark`() = lightAndDark("recordings_app_empty", RecordingsSamples.empty)

    @Test
    @Config(qualifiers = "+h1800dp", fontScale = 2.0f)
    fun `empty in Light at 200 percent`() = capture("recordings_app_empty_light_font200", RecordingsSamples.empty, PpsThemeMode.Light)

    @Test
    fun `recording in Light and Dark`() = lightAndDark("recordings_app_recording", RecordingsSamples.recording)

    @Test
    @Config(qualifiers = "+h1800dp", fontScale = 2.0f)
    fun `recording in Light at 200 percent`() =
        capture("recordings_app_recording_light_font200", RecordingsSamples.recording, PpsThemeMode.Light)

    @Test
    fun `after a stop in Light and Dark`() = lightAndDark("recordings_app_take", RecordingsSamples.take)

    @Test
    @Config(qualifiers = "+h1800dp", fontScale = 2.0f)
    fun `after a stop in Light at 200 percent`() = capture("recordings_app_take_light_font200", RecordingsSamples.take, PpsThemeMode.Light)

    @Test
    fun `a list with a missing file in Light and Dark`() = lightAndDark("recordings_app_missing", RecordingsSamples.listWithMissing)

    @Test
    @Config(qualifiers = "+h1800dp", fontScale = 2.0f)
    fun `a list with a missing file in Light at 200 percent`() =
        capture("recordings_app_missing_light_font200", RecordingsSamples.listWithMissing, PpsThemeMode.Light)

    @Test
    fun `microphone off in Light and Dark`() = lightAndDark("recordings_app_mic_denied", RecordingsSamples.micDenied)

    @Test
    @Config(qualifiers = "+h1800dp", fontScale = 2.0f)
    fun `microphone off in Light at 200 percent`() =
        capture("recordings_app_mic_denied_light_font200", RecordingsSamples.micDenied, PpsThemeMode.Light)

    @Test
    fun `Motivation with Random chosen in Light and Dark`() {
        captureEditor("editor_motivation_app_random_light", RecordingsSamples.motivationRandom, PpsThemeMode.Light) {
            composeRule.onNode(hasText("Random") and isRadio()).assertIsSelected()
            assertEquals(0, composeRule.onAllNodesWithText("When it plays").fetchSemanticsNodes().size, "Story 7.5")
        }
        captureEditor("editor_motivation_app_random_dark", RecordingsSamples.motivationRandom, PpsThemeMode.Dark)
    }

    @Test
    @Config(qualifiers = "+h1800dp", fontScale = 2.0f)
    fun `Motivation with Random chosen in Light at 200 percent`() =
        captureEditor("editor_motivation_app_random_light_font200", RecordingsSamples.motivationRandom, PpsThemeMode.Light)

    @Test
    fun `Motivation with a missing file in Light and Dark`() {
        captureEditor("editor_motivation_app_missing_light", RecordingsSamples.motivationMissing, PpsThemeMode.Light) {
            composeRule.onNode(hasText("Message 2", substring = true) and isRadio()).assertIsSelected()
            composeRule.onNodeWithText("File missing. Default sound will play.", useUnmergedTree = true).assertExists()
        }
        captureEditor("editor_motivation_app_missing_dark", RecordingsSamples.motivationMissing, PpsThemeMode.Dark)
    }

    @Test
    @Config(qualifiers = "+h1800dp", fontScale = 2.0f)
    fun `Motivation with a missing file in Light at 200 percent`() =
        captureEditor("editor_motivation_app_missing_light_font200", RecordingsSamples.motivationMissing, PpsThemeMode.Light)

    @Test
    fun `Random is offered only with two or more messages, and the main row names the chosen message`() {
        withScreen(
            PpsThemeMode.Light,
            content = { AlarmEditorScreen(state = RecordingsSamples.motivationOne, is24Hour = false, onIntent = {}) },
        ) {
            assertEquals(0, composeRule.onAllNodesWithText("Random").fetchSemanticsNodes().size)
            composeRule.onNode(hasText("None") and isRadio()).assertIsSelected()
            composeRule.onNode(hasText("Record a message") and hasClickAction()).assertExists()
        }
        withScreen(
            PpsThemeMode.Light,
            content = { AlarmEditorScreen(state = RecordingsSamples.editorChosen, is24Hour = false, onIntent = {}) },
        ) {
            composeRule
                .onNode(
                    hasText("Motivation", substring = true) and hasText("Message 1", substring = true) and hasClickAction(),
                ).assertExists()
            assertEquals(0, composeRule.onAllNodesWithText("After I'm up", substring = true).fetchSemanticsNodes().size)
        }
        // A stored Random with one message left stays offered and selected.
        val oneRandom =
            RecordingsSamples.motivationOne.copy(
                full = RecordingsSamples.motivationOne.full?.copy(motivation = MotivationChoice.Random),
            )
        withScreen(PpsThemeMode.Light, content = { AlarmEditorScreen(state = oneRandom, is24Hour = false, onIntent = {}) }) {
            composeRule.onNode(hasText("Random") and isRadio()).assertIsSelected()
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 2.0f)
    fun `every target is at least 48 dp and the record button is 72 dp, at 200 percent on a small phone`() {
        listOf(RecordingsSamples.listWithMissing, RecordingsSamples.take, RecordingsSamples.micDenied).forEach { state ->
            withScreen(PpsThemeMode.Light, content = { RecordingsScreen(state = state, onIntent = {}) }) {
                val density = composeRule.density
                val targets = composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
                assertTrue(targets.isNotEmpty())
                targets.forEach { node ->
                    val size = with(density) { node.size.width.toDp() to node.size.height.toDp() }
                    assertTrue(size.first >= 48.dp && size.second >= 48.dp, "$size: ${node.config}")
                }
            }
        }
        withScreen(PpsThemeMode.Light, content = { RecordingsScreen(state = RecordingsSamples.empty, onIntent = {}) }) {
            val record = composeRule.onNodeWithContentDescription("Start recording").fetchSemanticsNode()
            with(composeRule.density) { assertTrue(record.size.width.toDp() >= 72.dp) }
        }
    }

    @Test
    fun `while recording the time is a polite live region that changes every 10 s, and Stop recording is labelled`() {
        withScreen(PpsThemeMode.Light, content = { RecordingsScreen(state = RecordingsSamples.recording, onIntent = {}) }) {
            val time = composeRule.onNode(hasContentDescription("0:10 / 1:00")).fetchSemanticsNode()
            assertEquals(LiveRegionMode.Polite, time.config[SemanticsProperties.LiveRegion])
            composeRule.onNodeWithContentDescription("Stop recording").assertIsEnabled()
        }
    }

    @Test
    fun `a missing message cannot be previewed but can be deleted, and the mic-off record button is disabled`() {
        val intents = mutableListOf<RecordingsIntent>()
        withScreen(
            PpsThemeMode.Light,
            content = { RecordingsScreen(state = RecordingsSamples.listWithMissing, onIntent = { intents += it }) },
        ) {
            composeRule.onAllNodesWithContentDescription("Play message")[1].assertIsNotEnabled()
            composeRule.onAllNodesWithContentDescription("Play message")[0].performClick()
            composeRule.onNodeWithContentDescription("Delete Message 2").performClick()
            assertEquals(listOf<RecordingsIntent>(RecordingsIntent.PlayToggled("r1"), RecordingsIntent.DeleteClicked("r2")), intents)
        }
        withScreen(PpsThemeMode.Light, content = { RecordingsScreen(state = RecordingsSamples.micDenied, onIntent = {}) }) {
            composeRule.onNodeWithContentDescription("Start recording").assertIsNotEnabled()
            composeRule.onNodeWithText("Microphone is off. Turn it on in Settings.").assertExists()
        }
    }

    @Test
    fun `Record a message in the Motivation sub-screen asks the editor to open Recordings`() {
        val intents = mutableListOf<EditorIntent>()
        withScreen(PpsThemeMode.Light, content = {
            AlarmEditorScreen(state = RecordingsSamples.motivationRandom, is24Hour = false, onIntent = {
                intents +=
                    it
            })
        }) {
            composeRule.onNode(hasText("Record a message") and hasClickAction()).performClick()
            composeRule.onNode(hasText("Message 1") and isRadio()).performClick()
        }
        assertEquals(listOf(EditorIntent.RecordMessageClicked, EditorIntent.MotivationChosen(MotivationChoice.Recording("r1"))), intents)
    }

    private fun isRadio(): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
}

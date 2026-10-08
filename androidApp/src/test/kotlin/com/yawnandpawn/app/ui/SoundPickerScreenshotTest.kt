package com.yawnandpawn.app.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.sound.SoundRef
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.editor.AlarmEditorScreen
import com.yawnandpawn.app.ui.editor.EditorIntent
import com.yawnandpawn.app.ui.editor.EditorPane
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.editor.editorSound
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Sound sub-screen states of Story 1.17, built with the editor's real section builder. */
object SoundSamples {
    private val system =
        listOf(
            SoundRef.System("content://media/internal/audio/media/1", "Argon"),
            SoundRef.System("content://media/internal/audio/media/2", "Oxygen"),
        )
    private val gone = SoundRef.System("content://media/internal/audio/media/9", "Helium")

    /** The list with the default chosen. */
    val list =
        EditorUiState(pane = EditorPane.Sound, sound = editorSound(Alarm.DEFAULT_SOUND_REF, system, missing = false, previewing = null))

    /** Chimes chosen, its preview playing. */
    val selected =
        EditorUiState(
            pane = EditorPane.Sound,
            sound = editorSound("builtin:chimes", system, missing = false, previewing = "builtin:chimes"),
        )

    /** A chosen ringtone that is gone: its own row reads "File missing. Default sound will play.". */
    val missing = EditorUiState(pane = EditorPane.Sound, sound = editorSound(gone.encode(), system, missing = true, previewing = null))

    /** The editor's main screen for that missing choice: the Sound row and its note. */
    val missingOnEditor = missing.copy(pane = EditorPane.Main)

    /** The editor's main screen with Chimes chosen. */
    val chimesOnEditor = EditorUiState(sound = editorSound("builtin:chimes", system, missing = false, previewing = null))
}

/**
 * Story 1.17 screenshots of the editor's Sound sub-screen (the list, a selected sound with its preview playing, and a
 * missing ringtone) and the editor with a missing sound, in Light, Dark and Light at 200% font scale. Tall enough
 * that the whole list is in the picture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1800dp-mdpi")
class SoundPickerScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun capture(
        name: String,
        state: EditorUiState,
        mode: PpsThemeMode,
        checks: () -> Unit = {},
    ) = withScreen(mode, content = { AlarmEditorScreen(state = state, is24Hour = false, onIntent = {}) }) {
        checks()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    @Test
    fun `sound list in Light`() =
        capture("sound_list_light", SoundSamples.list, PpsThemeMode.Light) {
            assertTrue(composeRule.onAllNodesWithText("Built-in").fetchSemanticsNodes().isNotEmpty(), "the Built-in section")
            assertTrue(composeRule.onAllNodesWithText("System").fetchSemanticsNodes().isNotEmpty(), "the System section")
            composeRule.onNode(hasText("Sunrise") and isRadio()).assertIsSelected()
            assertEquals(0, composeRule.onAllNodesWithText("Your files").fetchSemanticsNodes().size, "Story 7.4")
            assertEquals(14, composeRule.onAllNodesWithContentDescription("Play preview").fetchSemanticsNodes().size)
        }

    @Test
    fun `sound list in Dark`() = capture("sound_list_dark", SoundSamples.list, PpsThemeMode.Dark)

    @Test
    @Config(qualifiers = "+h3600dp", fontScale = 2.0f)
    fun `sound list in Light at 200 percent`() = capture("sound_list_light_font200", SoundSamples.list, PpsThemeMode.Light)

    @Test
    fun `selected sound with its preview playing in Light`() =
        capture("sound_selected_light", SoundSamples.selected, PpsThemeMode.Light) {
            composeRule.onNode(hasText("Chimes") and isRadio()).assertIsSelected()
            composeRule.onNodeWithContentDescription("Stop preview").assertExists()
        }

    @Test
    fun `selected sound with its preview playing in Dark`() = capture("sound_selected_dark", SoundSamples.selected, PpsThemeMode.Dark)

    @Test
    @Config(qualifiers = "+h3600dp", fontScale = 2.0f)
    fun `selected sound with its preview playing in Light at 200 percent`() =
        capture("sound_selected_light_font200", SoundSamples.selected, PpsThemeMode.Light)

    @Test
    fun `missing ringtone row in Light`() =
        capture("sound_missing_light", SoundSamples.missing, PpsThemeMode.Light) {
            // The note above the list and the row's own caption.
            assertEquals(
                2,
                composeRule.onAllNodesWithText("File missing. Default sound will play.", useUnmergedTree = true).fetchSemanticsNodes().size,
            )
            composeRule.onNode(hasText("Helium") and isRadio()).assertIsSelected()
            assertEquals(15, composeRule.onAllNodesWithContentDescription("Play preview").fetchSemanticsNodes().size)
            composeRule.onAllNodesWithContentDescription("Play preview")[14].assertIsNotEnabled()
        }

    @Test
    fun `missing ringtone row in Dark`() = capture("sound_missing_dark", SoundSamples.missing, PpsThemeMode.Dark)

    @Test
    @Config(qualifiers = "+h3600dp", fontScale = 2.0f)
    fun `missing ringtone row in Light at 200 percent`() = capture("sound_missing_light_font200", SoundSamples.missing, PpsThemeMode.Light)

    @Test
    fun `editor with a missing sound in Light`() =
        capture("alarm_editor_sound_missing_light", SoundSamples.missingOnEditor, PpsThemeMode.Light) {
            assertTrue(composeRule.onAllNodesWithText("Helium", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "the Sound row")
            composeRule.onAllNodesWithText("File missing. Default sound will play.", useUnmergedTree = true)[0].assertExists()
        }

    @Test
    fun `editor with a missing sound in Dark`() =
        capture("alarm_editor_sound_missing_dark", SoundSamples.missingOnEditor, PpsThemeMode.Dark)

    @Test
    fun `the editor's Sound row names the chosen built-in sound`() =
        withScreen(
            PpsThemeMode.Light,
            content = { AlarmEditorScreen(state = SoundSamples.chimesOnEditor, is24Hour = false, onIntent = {}) },
        ) {
            composeRule.onNodeWithText("Chimes", useUnmergedTree = true).assertExists()
            assertEquals(0, composeRule.onAllNodesWithText("Sunrise", useUnmergedTree = true).fetchSemanticsNodes().size)
            assertEquals(0, composeRule.onAllNodesWithText("File missing. Default sound will play.").fetchSemanticsNodes().size)
        }

    @Test
    fun `the volume slider starts at 10 percent, so it can never be set to a silent 0 (Epic 3 device check)`() {
        val sent = mutableListOf<EditorIntent>()
        withScreen(
            PpsThemeMode.Light,
            content = { AlarmEditorScreen(state = SoundSamples.list, is24Hour = false, onIntent = { sent += it }) },
        ) {
            val slider = composeRule.onNodeWithContentDescription("Volume")
            val range = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(Alarm.MIN_VOLUME_PERCENT.toFloat(), range.range.start)
            assertEquals(100f, range.range.endInclusive)

            slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }

            assertEquals(EditorIntent.VolumeChanged(Alarm.MIN_VOLUME_PERCENT), sent.filterIsInstance<EditorIntent.VolumeChanged>().last())
        }
    }

    private fun isRadio(): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
}

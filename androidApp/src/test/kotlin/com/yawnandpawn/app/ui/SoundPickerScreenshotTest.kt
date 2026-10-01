package com.yawnandpawn.app.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.sound.SoundRef
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.editor.AlarmEditorScreen
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
            composeRule.onAllNodesWithText("Built-in").fetchSemanticsNodes().isNotEmpty()
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
            composeRule.onAllNodesWithContentDescription("Play preview").fetchSemanticsNodes().size
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
            composeRule.onAllNodesWithText("Helium", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            composeRule.onAllNodesWithText("File missing. Default sound will play.", useUnmergedTree = true)[0].assertExists()
        }

    @Test
    fun `editor with a missing sound in Dark`() =
        capture("alarm_editor_sound_missing_dark", SoundSamples.missingOnEditor, PpsThemeMode.Dark)

    private fun isRadio(): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
}

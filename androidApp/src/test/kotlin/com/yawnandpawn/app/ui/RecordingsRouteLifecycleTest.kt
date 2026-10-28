package com.yawnandpawn.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.recording.AudioRecorder
import com.yawnandpawn.app.core.recording.DeleteRecording
import com.yawnandpawn.app.core.recording.RecordedTake
import com.yawnandpawn.app.core.recording.RecordingPreview
import com.yawnandpawn.app.core.recording.RecordingRepository
import com.yawnandpawn.app.core.recording.SaveRecording
import com.yawnandpawn.app.core.reliability.ReliabilityRecords
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
import com.yawnandpawn.app.ui.editor.AlarmEditorScreen
import com.yawnandpawn.app.ui.editor.EditorIntent
import com.yawnandpawn.app.ui.editor.EditorPane
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.recordings.MicrophoneAnswer
import com.yawnandpawn.app.ui.recordings.MicrophonePermission
import com.yawnandpawn.app.ui.recordings.RecordingsRoute
import com.yawnandpawn.app.ui.recordings.RecordingsSources
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 7.2: the Recordings route over fake ports: a stopping screen stops the preview, leaving the composition cancels a
 * recording and deletes an unsaved take, the screen coming back reads the microphone again, and inside the editor a
 * saved and a deleted message reach it as editor intents.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingsRouteLifecycleTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle
            get() = registry
    }

    /** Granted while [granted]; a request answers [answer]. */
    private class TestMicrophone(
        var granted: Boolean = true,
        var answer: MicrophoneAnswer = MicrophoneAnswer.Denied,
    ) : MicrophonePermission {
        override fun isGranted(): Boolean = granted

        override suspend fun request(): MicrophoneAnswer = answer

        override fun openSettings() = Unit
    }

    private val recorder = FakeAudioRecorder()
    private val recordings = FakeRecordingRepository()
    private val preview = FakeRecordingPreview()
    private val microphone = TestMicrophone()
    private val records = FakeReliabilityRecords()
    private val logger = FakeLogger()
    private val sessionLock =
        SessionLockGuard(MutableStateFlow<SessionState>(SessionState.Idle), MutableStateFlow(true), MutableStateFlow(false))
    private val save =
        SaveRecording(recordings, recorder, FakeIdGenerator(), FakeClock(Instant.parse("2027-03-03T06:00:00Z")), sessionLock, logger)
    private val delete = DeleteRecording(recordings, FakeAlarmRepository(), AlarmWriteLock(), sessionLock, logger)
    private val sources = RecordingsSources(recorder, recordings, save, delete, preview, microphone, records, logger)

    private fun click(label: String) = composeRule.onNodeWithContentDescription(label).performClick()

    @Test
    fun `the screen stopping stops the playing preview`() {
        runBlocking { recordings.save(aRecording(id = "r1"), RecordedTake("t1", 9_000)) }
        val owner = TestOwner()
        withScreen(
            PpsThemeMode.Light,
            content = {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    RecordingsRoute(onSaved = {}, onDeleted = {}, onBack = {}, sources = sources)
                }
            },
        ) {
            click("Play message")
            composeRule.runOnIdle { assertEquals("r1", preview.playing.value?.key) }

            composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }

            composeRule.runOnIdle {
                assertNull(preview.playing.value, "ON_STOP stops the preview")
                assertTrue(preview.stops > 0)
            }
        }
    }

    @Test
    fun `leaving the composition cancels a recording and deletes an unsaved take`() {
        var shown by mutableStateOf(true)
        withScreen(
            PpsThemeMode.Light,
            content = { if (shown) RecordingsRoute(onSaved = {}, onDeleted = {}, onBack = {}, sources = sources) },
        ) {
            click("Start recording")
            composeRule.runOnIdle { assertNotNull(recorder.recording) }
            composeRule.runOnIdle { shown = false }
            composeRule.runOnIdle { assertEquals(1, recorder.cancels, "the recording is cancelled") }

            composeRule.runOnIdle { shown = true }
            click("Start recording")
            click("Stop recording")
            composeRule.waitForIdle()
            composeRule.runOnIdle { shown = false }
            composeRule.runOnIdle { assertEquals(listOf(RecordedTake("take-2", 9_000)), recorder.discarded) }
        }
    }

    @Test
    fun `coming back after Fix reads the microphone again and clears the banner once granted`() {
        microphone.granted = false
        var resumed by mutableIntStateOf(0)
        withScreen(
            PpsThemeMode.Light,
            content = { RecordingsRoute(onSaved = {}, onDeleted = {}, onBack = {}, resumed = resumed, sources = sources) },
        ) {
            click("Start recording")
            composeRule.waitForIdle()
            assertEquals(1, banners())

            microphone.granted = true
            composeRule.runOnIdle { resumed++ }
            composeRule.waitForIdle()
            assertEquals(0, banners())
        }
    }

    private fun banners() = composeRule.onAllNodesWithText("Microphone is off. Turn it on in Settings.").fetchSemanticsNodes().size

    @Test
    fun `inside the editor a saved and a deleted message arrive as editor intents`() {
        loadKoinModules(
            module {
                single<AudioRecorder> { recorder }
                single<RecordingRepository> { recordings }
                single<RecordingPreview> { preview }
                single<MicrophonePermission> { microphone }
                single<ReliabilityRecords> { records }
                factory { save }
                factory { delete }
            },
        )
        val intents = mutableListOf<EditorIntent>()
        withScreen(
            PpsThemeMode.Light,
            content = {
                AlarmEditorScreen(state = EditorUiState(pane = EditorPane.Recordings), is24Hour = false, onIntent = { intents += it })
            },
        ) {
            click("Start recording")
            click("Stop recording")
            composeRule.onNode(hasText("Save") and hasClickAction()).performClick()
            composeRule.waitForIdle()
            val id = recordings.stored.single().id
            composeRule.runOnIdle { assertEquals(listOf<EditorIntent>(EditorIntent.RecordingSaved(id)), intents) }

            click("Delete Message 1")
            composeRule.onNode(hasText("Delete") and hasClickAction()).performClick()
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertEquals(EditorIntent.RecordingDeleted(id), intents.last()) }
        }
    }
}

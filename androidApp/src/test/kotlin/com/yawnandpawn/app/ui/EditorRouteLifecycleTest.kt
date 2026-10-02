package com.yawnandpawn.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeNotificationPermission
import com.yawnandpawn.app.testing.FakeSoundLibrary
import com.yawnandpawn.app.testing.FakeSoundPreview
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.ui.editor.AlarmEditorRoute
import com.yawnandpawn.app.ui.editor.AlarmEditorViewModel
import com.yawnandpawn.app.ui.editor.EditorIntent
import com.yawnandpawn.app.ui.home.AlarmActions
import com.yawnandpawn.app.ui.sound.SoundPickerIntent
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Story 1.17: the editor route stops a Sound preview when its screen stops (the app goes to the background). */
@RunWith(RobolectricTestRunner::class)
class EditorRouteLifecycleTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle
            get() = registry
    }

    @Test
    fun `the screen stopping stops the playing preview`() {
        val repository = FakeAlarmRepository()
        val alarms = AlarmUseCasesFixture(repository = repository)
        val preview = FakeSoundPreview()
        val viewModel =
            AlarmEditorViewModel(
                alarmId = null,
                repository = repository,
                saveAlarm = alarms.save,
                clock = FakeClock(),
                timeZoneProvider = FakeTimeZoneProvider(),
                actions = AlarmActions(alarms.setEnabled, alarms.duplicate, alarms.delete, alarms.clock, FakeLogger()),
                soundLibrary = FakeSoundLibrary(),
                soundPreview = preview,
                notificationPermission = FakeNotificationPermission(),
            )
        val owner = TestOwner()
        withScreen(
            PpsThemeMode.Light,
            content = {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    AlarmEditorRoute(alarmId = null, onClose = {}, onOpenFailed = {}, onOpenCopy = {}, viewModel = viewModel)
                }
            },
        ) {
            composeRule.runOnIdle { viewModel.onIntent(EditorIntent.Sound(SoundPickerIntent.PreviewToggled("builtin:bell"))) }
            composeRule.runOnIdle { assertEquals(0, preview.stops) }

            composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }

            composeRule.runOnIdle {
                assertEquals(1, preview.stops, "ON_STOP stops the preview")
                assertNull(preview.previewing.value)
            }
        }
    }
}

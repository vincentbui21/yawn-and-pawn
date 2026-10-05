package com.yawnandpawn.app

import android.app.Activity
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.yawnandpawn.app.android.wake.WakeActivity
import com.yawnandpawn.app.core.session.CheckAnswer
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.time.MonotonicClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Story 2.5 on the managed device (FR-SES-4): a debug-fired alarm rings, the user presses Home, opens the notification
 * shade and taps the ringing notification. The wake screen is resumed within 1,000 ms of the tap. After three taps
 * there is still exactly one wake screen.
 *
 * Underscored names: the test APK is dexed below DEX 040 (minSdk 26), which rejects spaces in method names.
 */
@RunWith(AndroidJUnit4::class)
class ReturnThroughNotificationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val packageName = instrumentation.targetContext.packageName

    private val engine: SessionEngine
        get() = GlobalContext.get().get()

    /** The app's own monotonic clock port (the system clocks are read only by its adapters). */
    private val clock: MonotonicClock
        get() = GlobalContext.get().get()

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            device.executeShellCommand("pm grant $packageName android.permission.POST_NOTIFICATIONS")
        }
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
    }

    @After
    fun tearDown() {
        // End the session the way the wake screen does (Epic 1 placeholder check), so nothing keeps ringing.
        runBlocking {
            if (engine.state.value is SessionState.Ringing) engine.dispatch(SessionEvent.ImUpTapped)
            engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))
        }
        device.pressHome()
    }

    @Test
    fun tapping_the_notification_after_Home_resumes_the_one_wake_screen_within_a_second() {
        device.executeShellCommand("am broadcast -a com.yawnandpawn.app.debug.FIRE --ei seconds 2")
        awaitTrue("the debug alarm rings", RING_TIMEOUT_MS) { engine.state.value is SessionState.Ring }

        repeat(TAPS) { tap ->
            device.pressHome()
            awaitTrue("the wake screen leaves the front", STEP_TIMEOUT_MS) { resumedWakeScreens().isEmpty() }
            assertTrue("the alarm keeps ringing after Home", engine.state.value is SessionState.Ring)

            device.openNotification()
            val notification = device.wait(Until.findObject(By.textContains(NOTIFICATION_TEXT)), STEP_TIMEOUT_MS)
            checkNotNull(notification) { "the ringing notification is in the shade (tap ${tap + 1})" }
            val tappedAt = clock.elapsedMillis()
            notification.click()
            awaitTrue("the wake screen is resumed (tap ${tap + 1})", STEP_TIMEOUT_MS) { resumedWakeScreens().isNotEmpty() }
            val took = clock.elapsedMillis() - tappedAt
            assertTrue("the wake screen came back in $took ms (tap ${tap + 1})", took <= RETURN_LIMIT_MS)
        }

        assertEquals("one wake screen after $TAPS taps", 1, liveWakeScreens().size)
    }

    private fun wakeScreens(vararg stages: Stage): List<Activity> {
        var found: List<Activity> = emptyList()
        instrumentation.runOnMainSync {
            val registry = ActivityLifecycleMonitorRegistry.getInstance()
            found = stages.flatMap { registry.getActivitiesInStage(it) }.filterIsInstance<WakeActivity>()
        }
        return found
    }

    private fun resumedWakeScreens() = wakeScreens(Stage.RESUMED)

    private fun liveWakeScreens() =
        wakeScreens(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)

    private fun awaitTrue(
        what: String,
        timeoutMs: Long,
        condition: () -> Boolean,
    ) {
        val end = clock.elapsedMillis() + timeoutMs
        while (!condition()) {
            check(clock.elapsedMillis() < end) { "timed out: $what" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val NOTIFICATION_TEXT = "Tap to return to your alarm"
        const val TAPS = 3
        const val RETURN_LIMIT_MS = 1_000L
        const val RING_TIMEOUT_MS = 30_000L
        const val STEP_TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
    }
}

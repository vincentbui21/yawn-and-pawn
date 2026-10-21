package com.yawnandpawn.app.ui

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.reliability.ChecklistRow
import com.yawnandpawn.app.core.reliability.DeviceInfo
import com.yawnandpawn.app.core.reliability.DeviceInfoSource
import com.yawnandpawn.app.core.reliability.DeviceMaker
import com.yawnandpawn.app.core.reliability.ReliabilityProbe
import com.yawnandpawn.app.core.reliability.ReliabilitySettings
import com.yawnandpawn.app.core.reliability.ReliabilityStatus
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.testing.FakeReliabilityProbe
import com.yawnandpawn.app.testing.FakeReliabilitySettings
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * Story 5.4 in the real app (its Koin graph, navigation and records DataStore; the probe, the system settings screens
 * and the phone's maker are fakes): Settings' row opens the checklist, Home's banner "Fix" opens it, a row fixed in the
 * system settings reads "OK" when the app comes back, and "Fix" on Manufacturer pushes the maker guidance over it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ReliabilityRouteTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val probe = FakeReliabilityProbe()
    private val settings = FakeReliabilitySettings()
    private val banner = "Alarms may not ring. Fix settings"

    private fun start(device: DeviceInfo = DeviceInfo(sdkInt = 34, maker = "Google", fingerprint = "google/1")) {
        restartKoin(
            app,
            module {
                single<ReliabilityProbe> { probe }
                single<ReliabilitySettings> { settings }
                single<DeviceInfoSource> { DeviceInfoSource { device } }
            },
        )
        runBlocking { GlobalContext.get().get<SessionEngine>().restore() }
    }

    /** Waits for [text], idling the paused main looper on each check (the records DataStore resumes on it). */
    private fun waitForText(
        text: String,
        shown: Boolean = true,
    ) = composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() == shown
    }

    /** The merged checklist row of [title] reads [status] ("OK"); waits for it. */
    private fun waitForRow(
        title: String,
        status: String,
    ) = composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.onAllNodes(hasText(title) and hasText(status)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `the Settings row opens the checklist over Settings, Back returns, and the banner Fix opens it too`() {
        probe.status = ReliabilityStatus.ALL_OK.copy(batteryOptimizationIgnored = false)
        start()
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithContentDescription("Settings").performClick()
            waitForText(banner)
            composeRule.onNode(hasText("Reliability checklist") and hasClickAction()).performScrollTo().performClick()

            waitForRow("Notifications", "OK")
            composeRule.onNode(hasText("Battery") and hasText("Stops Android from pausing your alarm.")).assertExists()
            composeRule.onNodeWithText("Ring a test alarm").assertExists()
            composeRule.onNodeWithContentDescription("Add alarm").assertDoesNotExist()

            composeRule.onNodeWithContentDescription("Back").performClick()
            waitForText(banner)
            composeRule.onNode(hasText("Fix") and hasClickAction()).performClick()

            waitForText("Ring a test alarm")
            composeRule.onNode(hasText("Fix") and hasClickAction()).performClick()
            assertEquals(listOf(ChecklistRow.Battery), settings.opened)
        }
    }

    @Test
    fun `the Home banner Fix opens the checklist, a fixed row reads OK on return, and the banner then clears itself`() {
        probe.status = ReliabilityStatus.ALL_OK.copy(dndAllowsAlarms = false)
        start()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText(banner)
            composeRule.onNode(hasText("Fix") and hasClickAction()).performClick()
            waitForText("Ring a test alarm")
            composeRule.onNode(hasText("Do Not Disturb") and hasText("Do Not Disturb must allow alarms.")).assertExists()

            composeRule.onNode(hasText("Fix") and hasClickAction()).performClick()
            assertEquals(listOf(ChecklistRow.DoNotDisturb), settings.opened)

            // The system settings screen covers the app; the user allows alarms there and comes back.
            scenario.moveToState(Lifecycle.State.CREATED)
            probe.status = ReliabilityStatus.ALL_OK
            scenario.moveToState(Lifecycle.State.RESUMED)

            waitForRow("Do Not Disturb", "OK")
            composeRule.onNodeWithContentDescription("Back").performClick()
            waitForText("Add your first alarm")
            waitForText(banner, shown = false)
        }
    }

    @Test
    fun `Fix on Manufacturer pushes the maker guidance, and I've done this returns to the row read OK`() {
        start(DeviceInfo(sdkInt = 34, maker = "Xiaomi", fingerprint = "xiaomi/1", deviceMaker = DeviceMaker.Xiaomi))
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithContentDescription("Settings").performClick()
            waitForText("Reliability checklist")
            composeRule.onNode(hasText("Reliability checklist") and hasClickAction()).performScrollTo().performClick()
            waitForText("Manufacturer settings")

            composeRule.onNode(hasText("Fix") and hasClickAction()).performClick()

            waitForText("Turn on Autostart for Yawn & Pawn.")
            composeRule.onNode(hasText("I've done this") and hasClickAction()).performScrollTo().performClick()
            waitForRow("Manufacturer settings", "OK")
            assertEquals(emptyList(), settings.opened, "the maker guidance, not a system screen")
        }
    }
}

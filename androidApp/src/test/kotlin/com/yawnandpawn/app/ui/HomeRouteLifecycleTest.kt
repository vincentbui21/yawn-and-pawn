package com.yawnandpawn.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.history.MissedNotes
import com.yawnandpawn.app.core.reliability.ReliabilityStatus
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeCheckConfigRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMissedNoteDismissals
import com.yawnandpawn.app.testing.FakeReliabilityProbe
import com.yawnandpawn.app.testing.FakeReliabilitySettings
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeTimeChangeSignal
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.noReRegisterSuggestions
import com.yawnandpawn.app.ui.home.AlarmActions
import com.yawnandpawn.app.ui.home.HomeRoute
import com.yawnandpawn.app.ui.home.HomeViewModel
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue

/**
 * Story 1.19: the Home route checks the reliability settings again when it starts (back from "Fix") and when it
 * resumes (a permission dialog answered over Home only pauses it), and the banner follows.
 */
@RunWith(RobolectricTestRunner::class)
class HomeRouteLifecycleTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle
            get() = registry
    }

    private val banner = "Alarms may not ring. Fix settings"

    private fun bannerShown() = composeRule.onAllNodesWithText(banner).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the banner follows the settings after a stop and start, and after a pause and resume`() {
        val repository = FakeAlarmRepository()
        val alarms = AlarmUseCasesFixture(repository = repository)
        val probe = FakeReliabilityProbe(ReliabilityStatus.ALL_OK.copy(notificationsAllowed = false))
        val viewModel =
            HomeViewModel(
                FakeCheckConfigRepository(repository),
                AlarmActions(alarms.setEnabled, alarms.delete, alarms.clock, FakeLogger()),
                FakeClock(),
                FakeTimeZoneProvider(),
                FakeTimeChangeSignal(),
                MissedNotes(FakeSessionHistoryRepository(), FakeMissedNoteDismissals()),
                probe,
                FakeReliabilitySettings(),
                noReRegisterSuggestions(),
            )
        val owner = TestOwner()
        withScreen(
            PpsThemeMode.Light,
            content = {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    HomeRoute(onOpenEditor = {}, onOpenDuplicate = {}, openFailed = false, onOpenFailedShown = {}, viewModel = viewModel)
                }
            },
        ) {
            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) { bannerShown() }

            // Back from "Fix": stopped, the setting turned on, started again.
            composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
            val beforeStart = probe.checks
            probe.status = ReliabilityStatus.ALL_OK
            composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) { !bannerShown() }
            assertTrue(probe.checks > beforeStart, "ON_START checked again")

            // A permission dialog over Home: paused, the setting turned off, resumed.
            composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            val beforeResume = probe.checks
            probe.status = ReliabilityStatus.ALL_OK.copy(notificationsAllowed = false)
            composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) { bannerShown() }
            assertTrue(probe.checks > beforeResume, "ON_RESUME checked again")
        }
    }
}

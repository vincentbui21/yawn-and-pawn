package com.yawnandpawn.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.qr.FakeCameraPermission
import com.yawnandpawn.app.core.reliability.ReliabilityStatus
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeCheckConfigRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeGlobalSettingsRepository
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeReliabilityProbe
import com.yawnandpawn.app.testing.FakeReliabilitySettings
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.anEvaluateReliability
import com.yawnandpawn.app.ui.reliability.ChecklistTestAlarm
import com.yawnandpawn.app.ui.reliability.ReliabilityRoute
import com.yawnandpawn.app.ui.reliability.ReliabilityViewModel
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals

/**
 * Story 5.4 review item 4: the checklist route reads the rows once per return (the resume that ends every start), not on
 * both `ON_START` and `ON_RESUME`, and a pause and resume (a permission dialog) reads them once more.
 */
@RunWith(RobolectricTestRunner::class)
class ReliabilityRouteLifecycleTest {
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
    fun `one evaluation per stop and start, and one per pause and resume`() {
        val probe = FakeReliabilityProbe(ReliabilityStatus.ALL_OK.copy(dndAllowsAlarms = false))
        val clock = FakeClock()
        val logger = FakeLogger()
        val checks = FakeCheckConfigRepository()
        val testAlarm =
            ChecklistTestAlarm(
                checks,
                FakeGlobalSettingsRepository(),
                ScheduleTestAlarm(FakeAlarmScheduler(), FakeTestAlarmStore(), clock, logger),
                clock,
                FakeTimeZoneProvider(),
                logger,
            )
        val viewModel =
            ReliabilityViewModel(
                anEvaluateReliability(probe = probe, checks = checks),
                FakeReliabilitySettings(),
                FakeCameraPermission(),
                testAlarm,
                logger,
                // On the main looper, so each evaluation is done when the composition is idle and the count is exact.
                evaluationContext = EmptyCoroutineContext,
            )
        val owner = TestOwner()
        withScreen(
            PpsThemeMode.Light,
            content = {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    ReliabilityRoute(onBack = {}, onOpenManufacturerGuidance = {}, viewModel = viewModel)
                }
            },
        ) {
            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(hasText("Do Not Disturb")).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.runOnIdle { assertEquals(1, probe.checks, "the first resume") }

            // Back from a system settings screen: stopped, then started and resumed.
            composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
            composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            composeRule.runOnIdle { assertEquals(2, probe.checks, "one evaluation per start and resume") }

            // A permission dialog over the checklist: paused, then resumed.
            composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            composeRule.runOnIdle { assertEquals(3, probe.checks, "one evaluation per resume") }
        }
    }
}

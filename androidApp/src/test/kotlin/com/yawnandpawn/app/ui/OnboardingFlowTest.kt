package com.yawnandpawn.app.ui

import android.app.Application
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.valueOrNull
import com.yawnandpawn.app.core.onboarding.OnboardingProgress
import com.yawnandpawn.app.core.onboarding.OnboardingRepository
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.data.settings.SettingsDataStore
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.ui.onboarding.OnboardingViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 5.10 in the real app, with the real onboarding store (the settings DataStore) instead of the completed fake every
 * other app test uses: a first launch opens onboarding with no nav capsule, no alarm is saved before "I understand",
 * Back steps back, "Continue" on step 3 opens Home, a stored step 3 reopens there, and clearing the settings store
 * ("Delete all data") brings onboarding back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class OnboardingFlowTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val realOnboarding =
        module { single<OnboardingRepository> { get<SettingsDataStore>().onboarding(get()) } }

    private fun koinGet(): Koin = GlobalContext.get()

    private fun stored(): OnboardingProgress = runBlocking { assertNotNull(koinGet().get<OnboardingRepository>().get().valueOrNull()) }

    /** The real app with the real onboarding store, [before] run on it, then MainActivity. */
    private fun launch(
        before: suspend (OnboardingRepository) -> Unit = {},
        block: (ActivityScenario<MainActivity>) -> Unit,
    ) {
        restartKoin(app, realOnboarding)
        runBlocking {
            koinGet().get<SessionEngine>().restore()
            before(koinGet().get<OnboardingRepository>())
        }
        ActivityScenario.launch(MainActivity::class.java).use { block(it) }
    }

    /** Waits for [matcher], running the main looper, where the app's writes resume. */
    private fun waitFor(matcher: SemanticsMatcher) {
        composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForStored(check: (OnboardingProgress) -> Boolean) {
        composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
            shadowOf(Looper.getMainLooper()).idle()
            check(stored())
        }
    }

    /**
     * Taps [text] the way a person does: once the step has finished sliding in and has shown for longer than the
     * ViewModel's dwell time (the monotonic clock is the main looper's in Robolectric).
     */
    private fun tap(text: String) {
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(SETTLED)
        composeRule.onNode(hasText(text) and hasClickAction()).performClick()
    }

    /**
     * Two taps 100 ms apart on the place of the button [text] (a double tap): the second lands on whatever is there by
     * then, the outgoing button or the next step's identical button sliding in. Then the app settles.
     */
    private fun doubleTap(text: String) {
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(SETTLED)
        val at =
            composeRule
                .onNode(hasText(text) and hasClickAction())
                .fetchSemanticsNode()
                .boundsInRoot.center
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onRoot().performTouchInput { click(at) }
            composeRule.mainClock.advanceTimeBy(DOUBLE_TAP_GAP_MILLIS)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DOUBLE_TAP_GAP_MILLIS))
            composeRule.onRoot().performTouchInput { click(at) }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
    }

    /** Runs [block] with the animator duration scale at 0 (animations off), then back to 1. */
    private fun withAnimationsOff(block: () -> Unit) {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            block()
        } finally {
            Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
    }

    /** A double tap on "Let's set it up" opens the disclosure without accepting it; one on "I understand" never skips the fee. */
    private fun doubleTapsNeverSkipAStep() =
        launch {
            waitFor(mission)

            doubleTap("Let's set it up")

            waitFor(hasText("How your alarm works"))
            composeRule.onNode(hasContentDescription("Step 2 of 8")).assertExists()
            assertNull(stored().disclosureAcceptedAt, "the disclosure was never skipped")

            doubleTap("I understand")

            waitFor(hasText("Choose your snooze fee"))
            waitForStored { it.disclosureAcceptedAt != null }
            shadowOf(Looper.getMainLooper()).idleFor(SETTLED)
            composeRule.waitForIdle()
            composeRule.onNode(hasText("Choose your snooze fee")).assertExists()
            assertFalse(stored().completed, "the fee step was never skipped")
        }

    @Test
    fun `double taps never skip the disclosure or the fee step, with animations on`() = doubleTapsNeverSkipAStep()

    @Test
    fun `double taps never skip the disclosure or the fee step, with animations off`() = withAnimationsOff { doubleTapsNeverSkipAStep() }

    private val mission = hasText(MISSION)
    private val navCapsule = hasContentDescription("Settings") and hasClickAction()

    private fun saveAlarm(): Outcome<*, DomainError> = runBlocking { koinGet().get<SaveAlarm>()(AlarmDraft(time = LocalTime(7, 0))) }

    @Test
    fun `a first launch walks steps 1 to 3 with Back, the consent unlocks saving, and Continue opens Home`() =
        launch {
            waitFor(mission)
            composeRule.onNode(hasContentDescription("Step 1 of 8")).assertExists()
            composeRule.onNode(navCapsule).assertDoesNotExist()
            composeRule.onNode(hasContentDescription("Back")).assertDoesNotExist()
            assertEquals(Outcome.Failure(DomainError.DisclosureRequired), saveAlarm(), "no alarm before I understand")

            tap("Let's set it up")
            waitFor(hasText("How your alarm works"))
            composeRule.onNode(hasContentDescription("Step 2 of 8")).assertExists()
            composeRule.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
            waitFor(mission)

            tap("Let's set it up")
            waitFor(hasText("How your alarm works"))
            tap("I understand")
            waitFor(hasText("Choose your snooze fee"))
            waitForStored { it.disclosureAcceptedAt != null && it.step == "BaseFee" }
            composeRule.onNode(hasContentDescription("Step 3 of 8")).assertExists()
            assertIs<Outcome.Success<*>>(saveAlarm(), "saving works once the disclosure is accepted")

            composeRule.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
            waitFor(hasText("How your alarm works"))
            tap("I understand")
            waitFor(hasText("Choose your snooze fee"))
            tap("Continue")

            waitFor(navCapsule)
            assertTrue(stored().completed)
        }

    @Test
    fun `system Back on step 2 returns to the mission`() =
        launch { scenario ->
            waitFor(mission)
            tap("Let's set it up")
            waitFor(hasText("How your alarm works"))

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            waitFor(mission)
            waitForStored { it.step == "Mission" }
        }

    @Test
    fun `a step 3 stored before the process died reopens on step 3`() =
        launch(before = { onboarding ->
            onboarding.setDisclosureAccepted(Instant.parse("2027-03-08T21:00:00Z"))
            onboarding.setStep("BaseFee")
        }) {
            waitFor(hasText("Choose your snooze fee"))
            composeRule.onNode(hasContentDescription("Step 3 of 8")).assertExists()
            composeRule.onNode(navCapsule).assertDoesNotExist()
        }

    @Test
    fun `a completed onboarding opens Home, and clearing the settings store brings onboarding back`() =
        launch(before = { onboarding ->
            onboarding.setDisclosureAccepted(Instant.parse("2027-03-08T21:00:00Z"))
            onboarding.setCompleted()
        }) {
            waitFor(navCapsule)
            composeRule.onNode(mission).assertDoesNotExist()

            runBlocking { koinGet().get<SettingsDataStore>().clear() }

            waitFor(mission)
            composeRule.onNode(navCapsule).assertDoesNotExist()
        }

    private companion object {
        const val MISSION = "This app makes money only when you snooze. We hope you never pay us."

        /** Longer than the step slide (250 ms) and the ViewModel's dwell time (500 ms). */
        val SETTLED: Duration = Duration.ofMillis(OnboardingViewModel.STEP_DWELL_MILLIS + 100)

        const val DOUBLE_TAP_GAP_MILLIS = 100L
    }
}

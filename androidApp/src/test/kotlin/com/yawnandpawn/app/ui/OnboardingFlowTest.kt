package com.yawnandpawn.app.ui

import android.app.Application
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.valueOrNull
import com.yawnandpawn.app.core.onboarding.OnboardingProgress
import com.yawnandpawn.app.core.onboarding.OnboardingRepository
import com.yawnandpawn.app.core.reliability.DeviceInfo
import com.yawnandpawn.app.core.reliability.DeviceInfoSource
import com.yawnandpawn.app.core.reliability.DeviceMaker
import com.yawnandpawn.app.core.reliability.EvaluateReliability
import com.yawnandpawn.app.core.reliability.ReliabilityProbe
import com.yawnandpawn.app.core.reliability.ReliabilityRecords
import com.yawnandpawn.app.core.reliability.ReliabilityStatus
import com.yawnandpawn.app.core.reliability.RowStatus
import com.yawnandpawn.app.core.reset.DeleteAllData
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.telemetry.AnalyticsConsent
import com.yawnandpawn.app.core.telemetry.ConsentStore
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.data.settings.SettingsDataStore
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.testing.FakeReliabilityProbe
import com.yawnandpawn.app.ui.onboarding.OnboardingViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import com.yawnandpawn.app.core.reliability.ChecklistRow as CoreChecklistRow

/**
 * Stories 5.10 to 5.12 in the real app, with the real onboarding store (the settings DataStore) instead of the completed
 * fake every other app test uses: a first launch opens onboarding with no nav capsule, no alarm is saved before "I
 * understand", Back steps back, "Continue" on step 3 opens the first alarm, steps 4 to 7 with "No thanks" store one
 * enabled alarm and no usage stats and open step 8, whose "Skip for now" opens Home with the test note until "Dismiss"
 * (Story 5.12), a stored step 3 reopens there, and Story 5.9's real "Delete all data" brings onboarding back with no
 * consent.
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
        overrides: List<Module> = emptyList(),
        block: (ActivityScenario<MainActivity>) -> Unit,
    ) {
        restartKoin(app, realOnboarding, *overrides.toTypedArray())
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
    private fun doubleTap(
        text: String,
        matcher: SemanticsMatcher = hasText(text) and hasClickAction(),
    ) {
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(SETTLED)
        val at =
            composeRule
                .onNode(matcher)
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

    /** Taps [text] once the step that slid out has gone, so only the new step's button carries it. */
    private fun tapOnly(text: String) {
        composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().size == 1
        }
        tap(text)
    }

    private val mission = hasText(MISSION)
    private val navCapsule = hasContentDescription("Settings") and hasClickAction()

    private fun saveAlarm(): Outcome<*, DomainError> = runBlocking { koinGet().get<SaveAlarm>()(AlarmDraft(time = LocalTime(7, 0))) }

    @Test
    fun `a first launch walks steps 1 to 3 with Back, the consent unlocks saving, and Continue opens the first alarm`() =
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

            waitFor(hasText("When should it ring?"))
            composeRule.onNode(hasContentDescription("Step 4 of 8")).assertExists()
            waitForStored { it.step == "FirstAlarm" }
            assertFalse(stored().completed)
            composeRule.onNode(navCapsule).assertDoesNotExist()
        }

    @Test
    fun `steps 4 to 8 with No thanks and Skip for now save one enabled alarm, store no usage stats, and open Home with the note`() =
        launch(before = { onboarding ->
            onboarding.setDisclosureAccepted(Instant.parse("2027-03-08T21:00:00Z"))
            onboarding.setStep("BaseFee")
        }) {
            waitFor(hasText("Choose your snooze fee"))
            tapOnly("Continue")
            waitFor(hasText("When should it ring?"))
            tapOnly("Continue")
            waitFor(hasText("How will you prove you're up?"))
            composeRule.onNode(hasContentDescription("Step 5 of 8")).assertExists()
            tapOnly("Continue")

            waitFor(hasText("Make sure it rings"))
            waitForStored { it.step == "Reliability" && it.alarmId != null }
            tapOnly("Continue")
            waitFor(hasText("Share anonymous usage stats?"))
            // Neither choice is pre-selected or focused on the live screen (FR-ONB-6).
            composeRule.onNode(hasText("Share") and hasClickAction()).assertIsNotFocused()
            composeRule.onNode(hasText("No thanks") and hasClickAction()).assertIsNotFocused()
            tapOnly("No thanks")

            // Story 5.12: step 7 leads to the test alarm, not Home.
            waitFor(hasText("Test your alarm"))
            composeRule.onNode(hasContentDescription("Step 8 of 8")).assertExists()
            waitForStored { it.step == "TestAlarm" }
            assertFalse(stored().completed)
            composeRule.onNode(navCapsule).assertDoesNotExist()
            tapOnly("Skip for now")

            waitFor(navCapsule)
            assertTrue(stored().completed)
            assertTrue(stored().testNoteDue)
            waitFor(hasText(TEST_NOTE))
            val records = runBlocking { koinGet().get<ReliabilityRecords>().read().valueOrNull() }
            assertNull(assertNotNull(records).lockedTestPassedAt, "the checklist's test row stays unticked")
            val alarm = assertNotNull(runBlocking { koinGet().get<AlarmRepository>().listAll().valueOrNull() }).single()
            assertTrue(alarm.enabled)
            assertEquals(stored().alarmId, alarm.id)
            val consent = runBlocking { koinGet().get<ConsentStore>().observe().first() }
            assertEquals(AnalyticsConsent(consent = false, asked = true), consent)

            composeRule.onNode(hasContentDescription("Dismiss") and hasClickAction()).performClick()
            waitForStored { !it.testNoteDue }
            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
                shadowOf(Looper.getMainLooper()).idle()
                composeRule.onAllNodes(hasText(TEST_NOTE)).fetchSemanticsNodes().isEmpty()
            }

            composeRule.onNode(navCapsule).performClick()
            val usageStats = hasText("Share anonymous usage stats") and isToggleable()
            waitFor(usageStats)
            composeRule.onNode(usageStats).assertIsOff()
        }

    @Test
    fun `Ring a test alarm then a locked pass opens Home without the note, and the checklist's test row is OK`() =
        launch(before = { onboarding ->
            onboarding.setDisclosureAccepted(Instant.parse("2027-03-08T21:00:00Z"))
            val first = assertIs<Outcome.Success<Alarm>>(koinGet().get<SaveAlarm>()(AlarmDraft(time = LocalTime(7, 0)))).value
            onboarding.setAlarmId(first.id)
            onboarding.setStep("TestAlarm")
        }) {
            waitFor(hasText("Test your alarm"))
            tapOnly("Ring a test alarm")
            waitForStored { it.testRingsAt != null }
            // Armed: "Ring a test alarm" is disabled while the test is pending.
            waitFor(hasText("Ring a test alarm") and isNotEnabled())
            assertFalse(stored().completed)

            // What 5.3's reporter writes when the test, rung over the lock screen, is completed.
            val passedAt = koinGet().get<Clock>().now() + 20.seconds
            runBlocking { koinGet().get<ReliabilityRecords>().lockedTestPassed(passedAt) }

            waitFor(navCapsule)
            assertTrue(stored().completed)
            assertFalse(stored().testNoteDue)
            composeRule.onNode(hasText(TEST_NOTE)).assertDoesNotExist()
            val rows = runBlocking { koinGet().get<EvaluateReliability>()() }
            assertEquals(RowStatus.Ok, rows.single { it.row == CoreChecklistRow.LockedScreenTest }.status)
        }

    @Test
    fun `a check's row opens Check setup over step 5, and system Back returns to the checks, not to step 4`() =
        launch(before = { onboarding ->
            onboarding.setDisclosureAccepted(Instant.parse("2027-03-08T21:00:00Z"))
            onboarding.setStep("Checks")
        }) { scenario ->
            waitFor(hasText("How will you prove you're up?"))
            // The "Your checks" row of the default check (the "Checks" card's row is the toggleable one).
            val mathSetup = hasText("Math") and hasClickAction() and !isToggleable()
            waitFor(mathSetup)
            shadowOf(Looper.getMainLooper()).idleFor(SETTLED)
            composeRule.onNode(mathSetup).performScrollTo().performClick()
            waitFor(hasText("Try it") and hasClickAction())
            composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Math")).assertExists()

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            waitFor(hasText("How will you prove you're up?"))
            composeRule.waitForIdle()
            composeRule.onNode(hasContentDescription("Step 5 of 8")).assertExists()
            composeRule.onNode(hasText("Try it") and hasClickAction()).assertDoesNotExist()
            assertEquals("Checks", stored().step)
        }

    @Test
    fun `a double tap on a check's row opens Check setup without changing it`() =
        launch(before = { onboarding ->
            onboarding.setDisclosureAccepted(Instant.parse("2027-03-08T21:00:00Z"))
            onboarding.setStep("Checks")
        }) { scenario ->
            waitFor(hasText("How will you prove you're up?"))
            val mathSetup = hasText("Math") and hasClickAction() and !isToggleable()
            waitFor(mathSetup)
            composeRule.onNode(mathSetup).performScrollTo()
            val before = setupSummary(mathSetup)

            // The second tap lands on Check setup while it slides in: it counts for nothing.
            doubleTap("Math", matcher = mathSetup)

            waitFor(hasText("Try it") and hasClickAction())
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(hasText("How will you prove you're up?"))
            composeRule.waitForIdle()
            assertEquals(before, setupSummary(mathSetup), "Check setup is as it was")
        }

    /** The texts of the "Your checks" row [matcher]: the check's name and its setup ("Easy · 3 problems"). */
    private fun setupSummary(matcher: SemanticsMatcher): String =
        composeRule
            .onNode(matcher)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString { it.text }

    /** A first launch on step 6: the consent, an alarm saved before its id was stored (adopted), and the stored step. */
    private suspend fun onChecklist(onboarding: OnboardingRepository) {
        onboarding.setDisclosureAccepted(Instant.parse("2027-03-08T21:00:00Z"))
        assertIs<Outcome.Success<*>>(koinGet().get<SaveAlarm>()(AlarmDraft(time = LocalTime(7, 0))))
        onboarding.setStep("Reliability")
    }

    @Test
    fun `step 6 checks the rows again when the app resumes, after a system settings screen`() =
        launch(before = { onChecklist(it) }) { scenario ->
            val probe = koinGet().get<ReliabilityProbe>() as FakeReliabilityProbe
            probe.status = ReliabilityStatus.ALL_OK.copy(notificationsAllowed = false)
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor(hasText("Make sure it rings"))
            waitFor(hasText("Fix") and hasClickAction())

            // Back from the system screen with notifications on: paused, then resumed.
            probe.status = ReliabilityStatus.ALL_OK
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)

            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
                shadowOf(Looper.getMainLooper()).idle()
                composeRule.onAllNodes(hasText("Fix") and hasClickAction()).fetchSemanticsNodes().isEmpty()
            }
            assertEquals("Reliability", stored().step)
            assertNotNull(stored().alarmId, "the alarm saved before its id was stored is adopted")
        }

    @Test
    fun `Fix on the maker row opens the guidance as a pane, and every opening is its own`() =
        launch(before = { onChecklist(it) }, overrides = listOf(xiaomi)) { scenario ->
            waitFor(hasText("Make sure it rings"))
            val makerFix = hasText("Fix") and hasClickAction() and hasAnyAncestor(hasText("Manufacturer settings", substring = true))
            waitFor(makerFix)
            shadowOf(Looper.getMainLooper()).idleFor(SETTLED)
            composeRule.onNode(makerFix).performScrollTo().performClick()
            waitFor(hasText("I've done this") and hasClickAction())
            composeRule
                .onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Manufacturer settings"))
                .assertExists()

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(hasText("Make sure it rings"))
            shadowOf(Looper.getMainLooper()).idleFor(SETTLED)
            composeRule.onNode(makerFix).performScrollTo().performClick()

            // The second opening stays open (nothing left over from the first closes it).
            waitFor(hasText("I've done this") and hasClickAction())
            shadowOf(Looper.getMainLooper()).idleFor(SETTLED)
            composeRule.waitForIdle()
            composeRule.onNode(hasText("I've done this") and hasClickAction()).assertExists()
        }

    /** A Xiaomi phone, so the checklist has the Manufacturer row (Story 5.5). */
    private val xiaomi =
        module {
            single<DeviceInfoSource> {
                DeviceInfoSource {
                    DeviceInfo(
                        sdkInt = 34,
                        maker = "Xiaomi",
                        fingerprint = "xiaomi/test",
                        deviceMaker = DeviceMaker.Xiaomi,
                    )
                }
            }
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
    fun `a completed onboarding opens Home, and Delete all data brings onboarding back with no consent`() =
        launch(before = { onboarding ->
            onboarding.setDisclosureAccepted(Instant.parse("2027-03-08T21:00:00Z"))
            onboarding.setCompleted()
        }) {
            waitFor(navCapsule)
            composeRule.onNode(mission).assertDoesNotExist()
            assertIs<Outcome.Success<*>>(saveAlarm(), "the consent lets alarms be saved")

            // Story 5.9's real deletion: it clears the settings DataStore, the onboarding progress with it.
            assertEquals(Outcome.Success(Unit), runBlocking { koinGet().get<DeleteAllData>()() })

            waitFor(mission)
            composeRule.onNode(hasContentDescription("Step 1 of 8")).assertExists()
            composeRule.onNode(navCapsule).assertDoesNotExist()
            assertEquals(OnboardingProgress(), stored())
            assertEquals(Outcome.Failure(DomainError.DisclosureRequired), saveAlarm(), "no alarm before I understand again")
        }

    private companion object {
        const val MISSION = "This app makes money only when you snooze. We hope you never pay us."

        /** Home's note after "Skip for now" (Story 5.12). */
        const val TEST_NOTE = "Ring a test alarm with your phone locked to check it works."

        /** Longer than the step slide (250 ms) and the ViewModel's dwell time (500 ms). */
        val SETTLED: Duration = Duration.ofMillis(OnboardingViewModel.STEP_DWELL_MILLIS + 100)

        const val DOUBLE_TAP_GAP_MILLIS = 100L
    }
}

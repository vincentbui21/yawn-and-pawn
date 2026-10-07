package com.yawnandpawn.app

import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yawnandpawn.app.android.wake.AndroidAlarmPlayer
import com.yawnandpawn.app.android.wake.WakeActivity
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.DeleteAlarm
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.CheckRun
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.debug.DebugCheckAnswer
import com.yawnandpawn.app.debug.fire.DebugFire
import com.yawnandpawn.app.ui.qr.CameraProblem
import com.yawnandpawn.app.ui.qr.CodeScanner
import com.yawnandpawn.app.ui.qr.ScanEvent
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toLocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

/**
 * Story 3.12, flow F5 on the Gradle Managed Device (ATD API 34) with Compose accessibility checks on: a debug alarm with
 * a QR/Barcode check rings, and the camera reports no permission, as on a fresh install that never granted `CAMERA`
 * (no `pm revoke`, which would kill the process, and no camera needed). Every step is found by label, role and heading
 * and is a tap: the clock reads first, then "I'm up"; the camera message and "Can't do this check?" are focusable at
 * once; the Fallback check picker lists Math first; each problem reads in words and is typed on the pad with its answer
 * read back (the answers come from the debug-only [DebugCheckAnswer] hook); "Check" ends the session and the alarm
 * stops. No notification shade, system app or camera is needed; the wake screen is opened directly. The debug alarm is
 * deleted afterwards.
 *
 * Underscored names: with minSdk 26 the test APK is dexed below DEX 040, which rejects spaces in method names.
 */
@RunWith(AndroidJUnit4::class)
class FallbackTalkBackDeviceTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val koin get() = GlobalContext.get()

    private val engine: SessionEngine get() = koin.get()

    private val player: AndroidAlarmPlayer get() = koin.get()

    /** A camera without the permission: the scan reports it unavailable at once, as the CameraX scanner does. */
    private object NoPermissionScanner : CodeScanner {
        override fun cameraPermitted(): Boolean = false

        @Composable
        override fun Scan(
            torchOn: Boolean,
            onEvent: (ScanEvent) -> Unit,
        ) {
            LaunchedEffect(Unit) { onEvent(ScanEvent.CameraUnavailable(CameraProblem.NoPermission)) }
        }

        @Composable
        override fun Preview() = Unit

        @Composable
        override fun Feed(
            torchOn: Boolean,
            onEvent: (ScanEvent) -> Unit,
        ) = Scan(torchOn, onEvent)
    }

    /** The check run of the session in Grace or Loud; null otherwise. */
    private fun checkRun(): CheckRun? =
        (engine.state.value as? SessionState.Ring)?.takeIf { it !is SessionState.Ringing }?.session?.checkRun

    private fun button(label: String) = hasText(label) and hasClickAction()

    private fun label(node: SemanticsNode): String =
        node.config
            .getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ")
            ?.takeIf { it.isNotEmpty() }
            ?: node.config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .joinToString(" ") { it.text }

    /** The merged tree in reading order, with the traversal index (its own or its nearest ancestor's) moving a node. */
    private fun talkBackOrder(): List<String> {
        val nodes = mutableListOf<SemanticsNode>()

        fun visit(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::visit)
        }
        visit(composeRule.onRoot().fetchSemanticsNode())

        fun index(node: SemanticsNode): Float =
            generateSequence(node) { it.parent }.firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.TraversalIndex) } ?: 0f
        return nodes.sortedBy(::index).map(::label).filter { it.isNotEmpty() }
    }

    private fun headings(): List<String> =
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes().map(::label)

    @Test
    fun a_QR_alarm_without_the_camera_stops_with_taps_on_labels_through_Math() {
        // The Accessibility Test Framework checks the whole screen on every action (touch targets, labels); API 34+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) composeRule.enableAccessibilityChecks()
        val scanner = koin.get<CodeScanner>()
        loadKoinModules(module { single<CodeScanner> { NoPermissionScanner } })
        val alarmId = saveQrAlarm()
        try {
            val fire = DebugFire(koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get())
            val armed = runBlocking { fire.fire(DebugFire.FireRequest(seconds = 1, alarmId = alarmId)) }
            assertTrue("the alarm is armed", armed is Outcome.Success)
            composeRule.waitUntil(RING_TIMEOUT) { engine.state.value is SessionState.Ringing }

            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            ActivityScenario.launch<WakeActivity>(WakeActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
                imUpToTheCameraMessage()
                linkToMath()
                solveEveryProblem()
                poll { engine.state.value !is SessionState.Ring }
            }

            assertFalse("the check is passed: the session no longer rings", engine.state.value is SessionState.Ring)
            poll { !player.isRinging && player.sound == null }
            assertFalse("the ring is over", player.isRinging)
            assertNull("the alarm sound is released", player.sound)
        } finally {
            poll { engine.state.value == SessionState.Idle }
            runBlocking { koin.get<DeleteAlarm>()(alarmId) }
            loadKoinModules(module { single<CodeScanner> { scanner } })
        }
    }

    /** Saves a one-time alarm at the current minute with one QR/Barcode check and a registered code; returns its id. */
    private fun saveQrAlarm(): String {
        val code = checkNotNull(RegisteredCode.of(CodeFormat.Ean13, "4006381333931"))
        val now =
            koin
                .get<Clock>()
                .now()
                .toLocalDateTime(koin.get<TimeZoneProvider>().current())
                .time
        val draft =
            AlarmDraft(
                time = LocalTime(now.hour, now.minute),
                label = LABEL,
                checks = listOf(CheckEntry(CheckType.QrBarcode, Difficulty.Medium, count = 1, code = code)),
            )
        val saved = runBlocking { koin.get<SaveAlarm>()(draft) }
        assertTrue("the QR alarm is saved: $saved", saved is Outcome.Success)
        return (saved as Outcome.Success).value.id
    }

    /** Ringing reads the clock first, then "I'm up"; after it the camera message and the link are there, focusable. */
    private fun imUpToTheCameraMessage() {
        composeRule.waitUntil(STEP_TIMEOUT) { composeRule.onAllNodes(button("I'm up")).fetchSemanticsNodes().isNotEmpty() }
        val first = talkBackOrder().take(2)
        assertTrue("TalkBack starts on the clock: $first", CLOCK.matches(first[0]))
        assertEquals("I'm up", first[1])

        composeRule.onNode(button("I'm up")).performClick()
        composeRule.waitUntil(STEP_TIMEOUT) { composeRule.onAllNodes(button(LINK)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("Scan your code"), headings())
        composeRule.onNode(hasText("Camera isn't available. Pick a fallback check.")).assertExists()
        composeRule.onNode(button(LINK)).assert(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus))
    }

    /** The link opens the picker: its heading, "Back to check", then Math first, which is tapped. */
    private fun linkToMath() {
        composeRule.onNode(button(LINK)).performClick()
        composeRule.waitUntil(STEP_TIMEOUT) { headings() == listOf("Pick a fallback check") }
        val cards = talkBackOrder().filter { it == "Back to check" || CARD.containsMatchIn(it) }
        assertEquals("Back to check", cards.first())
        assertTrue("Math is the first card: $cards", cards[1].startsWith("Math"))
        composeRule.onNode(button("Math")).performClick()
        composeRule.waitUntil(STEP_TIMEOUT) { checkRun()?.fallbackUsed == true }
    }

    /** Each problem reads in words; its digits are typed by key label, the answer is read back, and "Check" sends it. */
    private fun solveEveryProblem() {
        repeat(MAX_PROBLEMS) {
            val answer = DebugCheckAnswer.current() ?: return
            composeRule.waitUntil(STEP_TIMEOUT) { headings().any { SPOKEN_PROBLEM.matches(it) } }
            val before = checkRun()
            answer.forEach { digit -> composeRule.onNode(button(digit.toString())).performClick() }
            composeRule.onNode(hasContentDescription("Answer $answer")).assertExists()
            composeRule.onNode(button("Check")).performClick()
            // The answer was decided: the next problem, or the session left Grace and Loud.
            composeRule.waitUntil(STEP_TIMEOUT) {
                val now = checkRun()
                now == null || now.step != before?.step || now.failedAttempts != before.failedAttempts
            }
            assertEquals("a right answer is never a failed attempt", before?.failedAttempts ?: 0, checkRun()?.failedAttempts ?: 0)
        }
    }

    /** Waits up to [timeoutMillis] for [condition], outside Compose: the wake screen closes when the session ends. */
    private fun poll(
        timeoutMillis: Long = STEP_TIMEOUT,
        condition: () -> Boolean,
    ) {
        repeat((timeoutMillis / POLL_MILLIS).toInt()) {
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val LABEL = "F5 device test"
        const val LINK = "Can't do this check?"
        val CARD = Regex("^(Math|Word|Memory)")
        const val POLL_MILLIS = 100L
        const val RING_TIMEOUT = 60_000L
        const val STEP_TIMEOUT = 10_000L
        const val MAX_PROBLEMS = 10
        val CLOCK = Regex("""^\d{1,2}:\d{2}(.[AP]M)?$""")
        val SPOKEN_PROBLEM = Regex("""^\d+ (plus|minus|times) \d+( (plus|minus|times) \d+)*$""")
    }
}

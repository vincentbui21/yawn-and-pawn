package com.yawnandpawn.app.android.qr

import android.content.Intent
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.android.wake.WakeActivity
import com.yawnandpawn.app.android.wake.WakeApp
import com.yawnandpawn.app.android.wake.WakeQr
import com.yawnandpawn.app.android.wake.awaitScreen
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.session.CameraFallbackPolicy
import com.yawnandpawn.app.core.session.CheckRun
import com.yawnandpawn.app.core.session.FallbackDecision
import com.yawnandpawn.app.core.session.FallbackPolicy
import com.yawnandpawn.app.core.session.FallbackReason
import com.yawnandpawn.app.core.session.FallbackRequest
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.launchActivity
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.ui.qr.ScanResult
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** The production fallback policy, keeping the reason of every request (the screen's and the engine's). */
internal class RecordingFallbackPolicy : FallbackPolicy {
    private val policy = CameraFallbackPolicy()
    private val asked = mutableListOf<FallbackRequest>()

    val reasons: List<FallbackReason>
        get() = synchronized(asked) { asked.map { it.reason } }

    override fun fallback(
        session: SessionData,
        request: FallbackRequest,
    ): FallbackDecision {
        synchronized(asked) { asked += request }
        return policy.fallback(session, request)
    }
}

/**
 * The wake QR check under test (Story 3.11): a [scanner], a virtual [monotonic] clock, a [fallback] policy that records
 * its requests, and the real Koin graph.
 */
internal class QrWake(
    private val composeRule: ComposeTestRule,
    val scanner: FakeCodeScanner = FakeCodeScanner(),
    val monotonic: FakeMonotonicClock = FakeMonotonicClock(elapsedMillis = START_MILLIS),
    val fallback: RecordingFallbackPolicy = RecordingFallbackPolicy(),
    /** The wall clock, for a test that moves it; the system's otherwise. */
    wall: FakeClock? = null,
) {
    val app = WakeApp(scanner = scanner, monotonic = monotonic, fallback = fallback, clock = wall)

    val toothpaste = ScanResult(CodeFormat.Ean13, "4006381333931")
    val code: RegisteredCode = assertNotNull(RegisteredCode.of(toothpaste.format, toothpaste.rawValue))

    /** A ring on a QR/Barcode entry with [codes] registered, one entry each (by default the toothpaste). */
    fun ring(
        sessionId: String = "session-qr",
        codes: List<RegisteredCode> = listOf(code),
    ) {
        val plan = CheckPlan(CheckMode.All, codes.map { CheckEntry(CheckType.QrBarcode, Difficulty.Medium, 1, code = it) })
        app.dispatch(SessionEvent.AlarmFired(sessionId, aSessionConfig().copy(checkPlan = plan), beforeFirstUnlock = false))
        assertIs<SessionState.Ringing>(app.engine.state.value)
    }

    /** Opens the Fallback check picker from the link. */
    fun openPicker() {
        composeRule.onNodeWithText(LINK).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Pick a fallback check").assertExists()
    }

    fun launch(): ActivityScenario<WakeActivity> = launchActivity(Intent(app.app, WakeActivity::class.java))

    fun run(): CheckRun = assertIs<SessionState.Active>(app.engine.state.value).session.checkRun

    fun imUp() {
        composeRule.onNodeWithText("I'm up").performClick()
        composeRule.awaitScreen(app, "Grace") {
            app.engine.state.value is SessionState.Grace
        }
    }

    /** Moves the monotonic clock to [millis] after [from], then lets the watchdog tick on the screen's clock. */
    fun at(
        from: Long,
        millis: Long,
    ) {
        monotonic.set(from + millis)
        composeRule.mainClock.advanceTimeBy(WakeQr.WATCHDOG_TICK.inWholeMilliseconds * 2)
        composeRule.waitForIdle()
    }

    /** One analyser heartbeat at the clock's [millis] after [from]. */
    fun heartbeatAt(
        from: Long,
        millis: Long,
    ) {
        monotonic.set(from + millis)
        scanner.heartbeat()
        composeRule.waitForIdle()
    }

    fun shows(text: String): Boolean = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    companion object {
        const val START_MILLIS = 1_000_000L
        const val UNAVAILABLE = "Camera isn't available. Pick a fallback check."
        const val LINK = "Can't do this check?"
        const val VIEWFINDER = "Camera viewfinder. Point at your code."
    }
}

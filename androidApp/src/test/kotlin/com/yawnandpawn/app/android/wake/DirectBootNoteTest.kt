package com.yawnandpawn.app.android.wake

import android.content.Intent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.call.CallState
import com.yawnandpawn.app.android.qr.FakeCodeScanner
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.session.DirectBootSubstitution
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.FakeUserLockState
import com.yawnandpawn.app.testing.aRegisteredCode
import com.yawnandpawn.app.testing.aSessionConfig
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Story 3.11 (with Story 2.3's Direct Boot): a QR/Barcode alarm that rings before the first unlock rings as Math, and the
 * Ringing and Check screens say "Your phone restarted, so today's check is Math."; the note and Math stay for that ring
 * after the unlock, and the next alarm after the unlock is QR/Barcode with no note ([FakeUserLockState]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DirectBootNoteTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val note = "Your phone restarted, so today's check is Math."
    private val qrPlan = CheckPlan(CheckMode.All, listOf(CheckEntry(CheckType.QrBarcode, Difficulty.Medium, 1, code = aRegisteredCode())))

    private fun ring(
        app: WakeApp,
        sessionId: String,
        locked: Boolean,
    ) {
        app.dispatch(SessionEvent.AlarmFired(sessionId, aSessionConfig().copy(checkPlan = qrPlan), beforeFirstUnlock = locked))
        assertIs<SessionState.Ringing>(app.engine.state.value)
    }

    private fun imUp(app: WakeApp) {
        composeRule.onNodeWithText("I'm up").performClick()
        app.awaitUntil("Grace") {
            composeRule.waitForIdle()
            app.engine.state.value is SessionState.Grace
        }
    }

    @Test
    fun `a locked QR ring is Math with the note on Ringing and Check, kept after the unlock, and the next alarm is QR with no note`() {
        val lock = FakeUserLockState(unlocked = false)
        val scanner = FakeCodeScanner()
        val app = WakeApp(userLock = lock, scanner = scanner)
        ring(app, "session-locked", locked = true)
        ActivityScenario.launch<WakeActivity>(Intent(app.app, WakeActivity::class.java)).use {
            composeRule.onNodeWithText(note).assertExists()
            imUp(app)
            composeRule.onNodeWithText("Problem 1 of 3").assertExists()
            composeRule.onNodeWithText(note).assertExists()
            assertEquals(0, scanner.starts, "no camera before the first unlock")

            lock.unlock()
            app.dispatch(SessionEvent.UserUnlocked)
            composeRule.waitForIdle()
            composeRule.onNodeWithText(note).assertExists()
            composeRule.onNodeWithText("Problem 1 of 3").assertExists()
            val session = assertIs<SessionState.Active>(app.engine.state.value).session
            assertEquals(listOf(DirectBootSubstitution.DIRECT_BOOT_CHECK), session.checkRun.plan.entries, "Math stays for the ring")

            app.solveCheck()
            app.awaitUntil("the locked session ended") {
                composeRule.waitForIdle()
                app.engine.state.value !is SessionState.Ring
            }
        }

        app.awaitUntil("idle") { app.engine.state.value == SessionState.Idle }
        ring(app, "session-unlocked", locked = false)
        ActivityScenario.launch<WakeActivity>(Intent(app.app, WakeActivity::class.java)).use {
            composeRule.onNodeWithText("I'm up").assertExists()
            composeRule.onNodeWithText(note).assertDoesNotExist()
            imUp(app)
            composeRule.onNodeWithText("Scan your code").assertExists()
            composeRule.onNode(hasContentDescription("Camera viewfinder. Point at your code.")).assertExists()
            composeRule.onNodeWithText(note).assertDoesNotExist()
            assertFalse(scanner.starts == 0, "QR/Barcode again, with the camera")
        }
    }

    @Test
    fun `a call during the locked ring shows the call note, and the Direct Boot note comes back after it`() {
        var inCall = false
        val calls =
            object : CallState {
                override fun inCall(): Boolean = inCall
            }
        val app = WakeApp(userLock = FakeUserLockState(unlocked = false), calls = calls)
        ring(app, "session-locked", locked = true)
        ActivityScenario.launch<WakeActivity>(Intent(app.app, WakeActivity::class.java)).use {
            composeRule.onNodeWithText(note).assertExists()
            inCall = true
            app.dispatch(SessionEvent.CallStarted)
            app.awaitUntil("the call pauses the ring") {
                composeRule.waitForIdle()
                assertIs<SessionState.Active>(app.engine.state.value).session.paused
            }
            composeRule.onNodeWithText("Paused for your call. Rings again when it ends.").assertExists()
            composeRule.onNodeWithText(note).assertDoesNotExist()

            inCall = false
            app.dispatch(SessionEvent.CallEnded)
            app.awaitUntil("the call ends") {
                composeRule.waitForIdle()
                !assertIs<SessionState.Active>(app.engine.state.value).session.paused
            }
            composeRule.onNodeWithText(note).assertExists()
        }
    }
}

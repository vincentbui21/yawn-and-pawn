package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.session.CheckRun
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.UnavailableReason
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.testing.aRegisteredCode
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** Story 3.10: the QR/Barcode Check screen mapping. */
class QrCheckMappingTest {
    private val start = TimeSnapshot(wallMillis = 1_800_000_000_000, elapsedMillis = 1_000_000, bootCount = 1)
    private val unavailable = SnoozeAvailability.Unavailable(UnavailableReason.CatalogueNotLoaded)
    private val qr = CheckPlan(CheckMode.All, listOf(CheckEntry(CheckType.QrBarcode, Difficulty.Medium, 1, code = aRegisteredCode())))

    private fun session(
        plan: CheckPlan = qr,
        failedAttempts: Int = 0,
    ): SessionData =
        aSession(config = aSessionConfig().copy(checkPlan = plan), startedAt = start).let {
            it.copy(checkRun = CheckRun(plan, listOf(3L), failedAttempts = failedAttempts))
        }

    private fun grace(session: SessionData = session()) = SessionState.Grace(session.copy(graceEnd = Deadline.after(start, 20.seconds)))

    private fun map(
        state: SessionState,
        input: CheckInput = CheckInput(),
        cameraAvailable: Boolean = true,
        torchOn: Boolean = false,
    ) = qrCheckUiState(state, unavailable, start, input, cameraAvailable, torchOn)

    @Test
    fun `a QR entry in grace shows the scanner with the countdown and the footer snooze`() {
        val ui = map(grace(), torchOn = true)!!

        assertEquals(CheckContent.QrBarcode(cameraAvailable = true, wrongCode = false, torchOn = true), ui.content)
        assertEquals(GraceState.Running(20, 20, vibrate = aSessionConfig().vibrateInGrace), ui.grace)
        assertEquals(SnoozeOffer.Unavailable(SnoozeUnavailableReason.PricesNotLoaded), ui.snooze)
        assertEquals(false, ui.showFallbackLink, "the link is Story 3.9's")
    }

    @Test
    fun `without the camera it says the camera is not available, at once`() {
        val content = assertIs<CheckContent.QrBarcode>(map(SessionState.Loud(session()), cameraAvailable = false)!!.content)

        assertEquals(false, content.cameraAvailable)
    }

    @Test
    fun `a different code shows the wrong-code line, keyed by the failed attempts`() {
        val before = checkPosition(grace())
        val after = grace(session(failedAttempts = 2))
        val input = CheckInput(before).following(checkPosition(after))

        val content = assertIs<CheckContent.QrBarcode>(map(after, input)!!.content)
        assertEquals(true, content.wrongCode)
        assertEquals(2, content.wrongAttempts)
    }

    @Test
    fun `another entry, Ringing or a passed check is not a QR screen`() {
        assertNull(map(grace(session(plan = CheckPlan.default()))))
        assertNull(map(SessionState.Ringing(session())))
        val passed = session().let { it.copy(checkRun = it.checkRun.copy(step = it.checkRun.step.copy(entry = 1))) }
        assertNull(map(grace(passed)))
        assertNull(mathCheckUiState(grace(), unavailable, start, CheckInput()), "the Math mapping leaves a QR entry alone")
    }
}

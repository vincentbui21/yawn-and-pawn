package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.session.CheckRun
import com.yawnandpawn.app.core.session.DirectBootSubstitution
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.UnavailableReason
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.testing.aRegisteredCode
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Story 3.11: the one note of the Ringing and Check screens. The phone-call note wins while a call pauses the ring; the
 * Direct Boot note shows on a ring whose QR/Barcode entry was swapped for Math, and comes back after the call.
 */
class WakeNoteTest {
    private val now = TimeSnapshot(wallMillis = 1_800_000_000_000, elapsedMillis = 1_000_000, bootCount = 1)
    private val locked = SnoozeAvailability.Unavailable(UnavailableReason.BeforeFirstUnlock)
    private val qrPlan = CheckPlan(CheckMode.All, listOf(CheckEntry(CheckType.QrBarcode, Difficulty.Medium, 1, code = aRegisteredCode())))

    /** A ring before the first unlock whose QR entry the reducer swapped for Math. */
    private fun swapped(): SessionData =
        aSession(config = aSessionConfig().copy(checkPlan = qrPlan)).let {
            it.copy(
                beforeFirstUnlock = true,
                directBootRing = true,
                checkRun = CheckRun(CheckPlan(CheckMode.All, listOf(DirectBootSubstitution.DIRECT_BOOT_CHECK)), listOf(1L)),
            )
        }

    @Test
    fun `no note on a plain ring, the phone-call note while paused`() {
        assertNull(wakeNote(aSession()))
        assertEquals(WakeNote.PhoneCall, wakeNote(aSession().copy(pausedAt = now)))
    }

    @Test
    fun `a swapped ring shows the Direct Boot note on the Ringing and the Math Check screen, also after the unlock`() {
        val session = swapped()
        assertEquals(WakeNote.DirectBoot, wakeNote(session))
        assertEquals(WakeNote.DirectBoot, ringingUiState(session, locked, TimeZone.UTC).note)
        assertEquals(WakeNote.DirectBoot, mathCheckUiState(SessionState.Loud(session), locked, now, CheckInput())?.note)

        val unlockedInRing = session.copy(beforeFirstUnlock = false)
        assertEquals(WakeNote.DirectBoot, wakeNote(unlockedInRing), "Math and the note stay for the ring")
        assertNull(wakeNote(unlockedInRing.copy(directBootRing = false, checkRun = CheckRun(qrPlan, listOf(1L)))), "the next ring")
    }

    @Test
    fun `a call during a swapped ring shows the phone-call note, and the Direct Boot note comes back after it`() {
        val session = swapped()
        assertEquals(WakeNote.PhoneCall, wakeNote(session.copy(pausedAt = now)))
        assertEquals(WakeNote.PhoneCall, ringingUiState(session.copy(pausedAt = now), locked, TimeZone.UTC).note)
        assertEquals(WakeNote.DirectBoot, wakeNote(session.copy(pausedAt = null)))
    }

    @Test
    fun `no note once the fallback replaced the check`() {
        val session = swapped()
        assertNull(wakeNote(session.copy(checkRun = session.checkRun.copy(fallbackUsed = true))))
    }

    @Test
    fun `the QR check of an unlocked ring has no note`() {
        val session = aSession(config = aSessionConfig().copy(checkPlan = qrPlan)).let { it.copy(checkRun = CheckRun(qrPlan, listOf(1L))) }
        val state = qrCheckUiState(SessionState.Loud(session), locked, now, CheckInput(), cameraAvailable = true)
        assertNull(state?.note)
        assertEquals(
            WakeNote.PhoneCall,
            qrCheckUiState(SessionState.Loud(session.copy(pausedAt = now)), locked, now, CheckInput(), true)?.note,
        )
    }
}

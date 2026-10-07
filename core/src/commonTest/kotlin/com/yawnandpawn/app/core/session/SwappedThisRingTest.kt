package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.time.Deadline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Story 3.11: [DirectBootSubstitution.swappedThisRing], the "Your phone restarted, so today's check is Math." note. It is
 * true only for a ring whose own pick had a camera entry swapped before the first unlock, through the real reducer.
 */
class SwappedThisRingTest {
    private object Locked : UserLockState {
        override fun isUserUnlocked(): Boolean = false

        override fun observe(): Flow<Boolean> = flowOf(false)
    }

    private val reducer = SessionReducer(NoBillingSnoozeAvailability(Locked), PluginCheckValidator, CameraFallbackPolicy())
    private val code = assertNotNull(RegisteredCode.of(CodeFormat.QrCode, "kitchen"))
    private val qr = CheckEntry(CheckType.QrBarcode, Difficulty.Medium, 1, code = code)
    private val word = CheckEntry(CheckType.WordUnscramble, Difficulty.Medium, 2)

    private fun plan(
        mode: CheckMode,
        vararg entries: CheckEntry,
    ) = CheckPlan(mode, entries.toList())

    private fun fired(
        plan: CheckPlan,
        locked: Boolean,
        sessionId: String = SESSION_ID,
    ): SessionData {
        val event = SessionEvent.AlarmFired(sessionId, testConfig(checkPlan = plan), beforeFirstUnlock = false)
        return assertIs<SessionState.Active>(reducer.reduce(SessionState.Idle, event, T0, userLocked = locked).state).session
    }

    /** A session id whose first ring's Random pick of [plan] is [type]. */
    private fun sessionPicking(
        plan: CheckPlan,
        type: CheckType,
    ): String =
        (0..200).map { "session-$it" }.first { id ->
            CheckRun
                .resolvedFor(plan, id, ringIndex = 1)
                .entries
                .single()
                .type == type
        }

    @Test
    fun `All with QR rung while locked is swapped for Math and noted, rung unlocked it is not`() {
        val locked = fired(plan(CheckMode.All, qr), locked = true)
        assertEquals(listOf(DirectBootSubstitution.DIRECT_BOOT_CHECK), locked.checkRun.plan.entries)
        assertTrue(DirectBootSubstitution.swappedThisRing(locked))

        val mixed = fired(plan(CheckMode.All, qr, word), locked = true)
        assertTrue(DirectBootSubstitution.swappedThisRing(mixed), "one swapped entry is enough")

        assertFalse(DirectBootSubstitution.swappedThisRing(fired(plan(CheckMode.All, qr), locked = false)))
    }

    @Test
    fun `a Random pick of QR is swapped and noted, a Random pick of Word changed nothing and is not`() {
        val random = plan(CheckMode.Random, qr, word)
        val pickedQr = fired(random, locked = true, sessionId = sessionPicking(random, CheckType.QrBarcode))
        val pickedWord = fired(random, locked = true, sessionId = sessionPicking(random, CheckType.WordUnscramble))

        assertEquals(
            CheckType.Math,
            pickedQr.checkRun.plan.entries
                .single()
                .type,
        )
        assertTrue(DirectBootSubstitution.swappedThisRing(pickedQr))
        assertEquals(
            CheckType.WordUnscramble,
            pickedWord.checkRun.plan.entries
                .single()
                .type,
        )
        assertFalse(DirectBootSubstitution.swappedThisRing(pickedWord), "the note means the check changed")
    }

    @Test
    fun `the note stays for the ring after an unlock in it, and the next ring after the unlock is QR with no note`() {
        val locked = fired(plan(CheckMode.All, qr), locked = true)
        val unlock = reducer.reduce(SessionState.Ringing(locked), SessionEvent.UserUnlocked, T0).state
        val unlockedInRing = assertIs<SessionState.Active>(unlock).session
        assertFalse(unlockedInRing.beforeFirstUnlock)
        assertTrue(DirectBootSubstitution.swappedThisRing(unlockedInRing), "Math and the note stay for this ring")

        val snoozed = unlockedInRing.copy(snoozesGranted = 1, interactionDeadline = null, snoozeEnd = Deadline.after(T0, 9.minutes))
        val nextUnlocked = reducer.reduce(SessionState.Snoozed(snoozed), SessionEvent.SlotFired, at(10.minutes), userLocked = false).state
        val next = assertIs<SessionState.Ringing>(nextUnlocked).session
        assertEquals(listOf(qr), next.checkRun.plan.entries, "QR/Barcode again")
        assertFalse(DirectBootSubstitution.swappedThisRing(next))

        val nextLocked = reducer.reduce(SessionState.Snoozed(snoozed), SessionEvent.SlotFired, at(10.minutes), userLocked = true).state
        assertTrue(
            DirectBootSubstitution.swappedThisRing(assertIs<SessionState.Ringing>(nextLocked).session),
            "still locked: swapped again",
        )
    }

    @Test
    fun `a ring restored while locked mid-ring is swapped and noted`() {
        val unlocked = fired(plan(CheckMode.All, qr), locked = false)
        val restored = reducer.reduce(SessionState.Loud(unlocked), SessionEvent.ProcessRestored, T0, userLocked = true).state
        assertTrue(DirectBootSubstitution.swappedThisRing(assertIs<SessionState.Active>(restored).session))
    }

    @Test
    fun `a locked ring restored unlocked after a kill keeps its swapped Math and the note (review)`() {
        val locked = fired(plan(CheckMode.All, qr), locked = true)
        val restored = reducer.reduce(SessionState.Loud(locked), SessionEvent.ProcessRestored, T0, userLocked = false).state
        val session = assertIs<SessionState.Active>(restored).session
        assertFalse(session.directBootRing, "restored unlocked")
        assertEquals(listOf(DirectBootSubstitution.DIRECT_BOOT_CHECK), session.checkRun.plan.entries, "Math stays mid-ring")
        assertTrue(DirectBootSubstitution.swappedThisRing(session), "so the note stays with it")
    }

    @Test
    fun `the note stays on every entry of the swapped ring, also after the swapped Math is passed`() {
        val locked = fired(plan(CheckMode.All, qr, word), locked = true)
        val onWord = locked.copy(checkRun = locked.checkRun.copy(step = StepPointer(entry = 1)))
        assertEquals(CheckType.WordUnscramble, onWord.checkRun.currentEntry?.type)
        assertTrue(DirectBootSubstitution.swappedThisRing(onWord))
    }

    @Test
    fun `no note once the fallback replaced the check, and none for a QR entry with no code (already Math)`() {
        val locked = fired(plan(CheckMode.All, qr), locked = true)
        val fallback = locked.copy(checkRun = locked.checkRun.copy(fallbackUsed = true))
        assertFalse(DirectBootSubstitution.swappedThisRing(fallback))

        val noCode = fired(plan(CheckMode.All, qr.copy(code = null)), locked = true)
        assertEquals(listOf(CheckPlan.DEFAULT_ENTRY), noCode.checkRun.plan.entries)
        assertFalse(DirectBootSubstitution.swappedThisRing(noCode), "PlanResolver made it Math: nothing was swapped")
    }

    @Test
    fun `a paused ring is still swapped (the screens decide which note shows)`() {
        val locked = fired(plan(CheckMode.All, qr), locked = true)
        assertTrue(DirectBootSubstitution.swappedThisRing(locked.copy(pausedAt = T0)))
    }
}

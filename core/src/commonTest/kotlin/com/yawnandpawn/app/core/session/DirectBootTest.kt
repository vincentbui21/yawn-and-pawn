package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Story 2.3: a ring before the first unlock after a reboot (FR-ALM-11, AD-15). */
class DirectBootTest {
    /** The lock state under test control (core cannot use :testing's fake). */
    private class Lock(
        unlocked: Boolean,
    ) : UserLockState {
        val state = MutableStateFlow(unlocked)

        override fun isUserUnlocked(): Boolean = state.value

        override fun observe(): Flow<Boolean> = state
    }

    private val systemSound = "system:Morning|content://media/internal/audio/media/7"
    private val lock = Lock(unlocked = false)
    private val time = EngineTime()
    private val store = InMemorySessionStore()
    private val runner = RecordingRunner()
    private val history = InMemoryHistory()

    private fun engine(policy: SnoozeAvailabilityPolicy = NoBillingSnoozeAvailability(lock)) =
        SessionEngine(
            SessionReducer(policy, PlaceholderCheckValidator, NoFallbackPolicy),
            store,
            runner,
            SessionRecorder(history),
            time.clock,
            time.monotonicClock,
            time.bootCounter,
            EngineLogger(),
            lock,
        )

    private fun alarmFired(config: SessionConfig = testConfig()) = SessionEvent.AlarmFired(SESSION_ID, config, beforeFirstUnlock = false)

    private fun Outcome<SessionState, *>.session(): SessionData =
        assertIs<SessionState.Active>(assertIs<Outcome.Success<SessionState>>(this).value).session

    @Test
    fun `before the first unlock a sound that needs normal storage becomes the default built-in sound`() {
        val config = testConfig().copy(soundRef = systemSound)

        assertEquals(Alarm.DEFAULT_SOUND_REF, DirectBootSubstitution.apply(config, beforeFirstUnlock = true).soundRef)
        assertEquals(Alarm.DEFAULT_SOUND_REF, DirectBootSubstitution.apply(config.copy(soundRef = "garbage"), true).soundRef)
    }

    @Test
    fun `a chosen built-in sound and the safe placeholder check stay, and unlocked the config comes back unchanged`() {
        val builtIn = testConfig().copy(soundRef = "builtin:birdsong", checkPlan = TWO_STEPS)
        val system = testConfig().copy(soundRef = systemSound)

        assertSame(builtIn, DirectBootSubstitution.apply(builtIn, beforeFirstUnlock = true))
        assertSame(system, DirectBootSubstitution.apply(system, beforeFirstUnlock = false))
        assertTrue(CheckType.Placeholder.directBootSafe)
        assertEquals(CheckEntry(CheckType.Placeholder, Difficulty.Medium, count = 1), DirectBootSubstitution.DIRECT_BOOT_CHECK)
    }

    @Test
    fun `an alarm that fires while locked rings before the first unlock with the default sound, its config frozen as it was`() =
        runTest {
            val config = testConfig().copy(soundRef = systemSound)

            val session = engine().dispatch(alarmFired(config)).session()

            assertTrue(session.beforeFirstUnlock)
            assertTrue(session.startedBeforeUnlock)
            assertEquals(config, session.config, "the frozen config keeps the chosen sound")
            assertEquals(EntryEffect.SoundAt(Alarm.DEFAULT_SOUND_REF, config.volumePercent), runner.entry.first())
        }

    @Test
    fun `a test alarm that fires while locked is marked too, and an unlocked fire is not`() =
        runTest {
            val test = engine().dispatch(SessionEvent.TestAlarmFired(SESSION_ID, testConfig(), beforeFirstUnlock = false)).session()
            assertTrue(test.beforeFirstUnlock)

            lock.state.value = true
            val unlocked = engine().also { store.row = null }.dispatch(alarmFired(testConfig().copy(soundRef = systemSound))).session()
            assertFalse(unlocked.beforeFirstUnlock)
            assertEquals(EntryEffect.SoundAt(systemSound, 80), runner.entry.last { it is EntryEffect.SoundAt })
        }

    @Test
    fun `a session that started unlocked and is restored while locked gets the substitutions for that ring, its ladder frozen`() =
        runTest {
            val config = testConfig().copy(soundRef = systemSound)
            store.commit(SessionState.Ringing(ringSession(config)))
            time.reboot(off = 1.hours)

            val restored = engine().restore().session()

            assertTrue(restored.beforeFirstUnlock)
            assertTrue(restored.startedBeforeUnlock, "history will say direct_boot")
            assertEquals(config, restored.config, "fee tier, max snoozes and snooze length frozen")
            assertEquals(EntryEffect.SoundAt(Alarm.DEFAULT_SOUND_REF, config.volumePercent), runner.entry.first())
        }

    @Test
    fun `a snooze that ends while locked rings the next ring before the first unlock`() =
        runTest {
            store.commit(SessionState.Snoozed(snoozedSession()))
            time.reboot(off = 2.hours)

            val next = engine().restore().session()

            assertEquals(2, next.ringIndex)
            assertTrue(next.beforeFirstUnlock)
        }

    @Test
    fun `a ring restored after the unlock plays the chosen sound again, history still says direct_boot`() =
        runTest {
            lock.state.value = true
            val config = testConfig().copy(soundRef = systemSound)
            store.commit(SessionState.Ringing(ringSession(config).copy(beforeFirstUnlock = true, startedBeforeUnlock = true)))

            val restored = engine().restore().session()

            assertFalse(restored.beforeFirstUnlock)
            assertTrue(restored.startedBeforeUnlock, "only ever goes from false to true")
            assertEquals(EntryEffect.SoundAt(systemSound, config.volumePercent), runner.entry.first())
        }

    @Test
    fun `a snooze that ends after the unlock rings the next ring with the chosen sound`() =
        runTest {
            lock.state.value = true
            val config = testConfig().copy(soundRef = systemSound)
            val lockedSnooze = snoozedSession().copy(config = config, beforeFirstUnlock = true, startedBeforeUnlock = true)
            store.commit(SessionState.Snoozed(lockedSnooze))
            time.reboot(off = 2.hours)

            val next = engine().restore().session()

            assertEquals(2, next.ringIndex)
            assertFalse(next.beforeFirstUnlock)
            assertTrue(next.startedBeforeUnlock)
            assertEquals(EntryEffect.SoundAt(systemSound, config.volumePercent), runner.entry.first { it is EntryEffect.SoundAt })
        }

    @Test
    fun `the locked plan swaps each unsafe step one for one and leaves a safe plan as it is`() {
        val swapped = DirectBootSubstitution.lockedPlan(TWO_STEPS, isSafe = { false })

        assertEquals(CheckPlan(CheckMode.All, List(2) { DirectBootSubstitution.DIRECT_BOOT_CHECK }), swapped)
        assertSame(TWO_STEPS, DirectBootSubstitution.lockedPlan(TWO_STEPS))

        val math = CheckEntry(CheckType.Math, Difficulty.Hard, count = 5)
        val mixed = CheckPlan(CheckMode.Random, listOf(math, CheckPlan.PLACEHOLDER_ENTRY))
        assertEquals(
            CheckPlan(CheckMode.Random, listOf(DirectBootSubstitution.DIRECT_BOOT_CHECK, CheckPlan.PLACEHOLDER_ENTRY)),
            DirectBootSubstitution.lockedPlan(mixed, isSafe = { it.type != CheckType.Math }),
            "only the unsafe entry is swapped, in place, and the mode stays",
        )
        assertSame(mixed, DirectBootSubstitution.lockedPlan(mixed), "Math is Direct Boot safe")
    }

    /** A reducer whose locked plan is [LOCKED]: every step counts as not Direct Boot safe (no such step exists yet). */
    private val marking =
        SessionReducer(
            NoBillingSnoozeAvailability(lock),
            PlaceholderCheckValidator,
            StubFallback(FallbackDecision.Allowed(FALLBACK_PLAN)),
            directBootPlan = { LOCKED },
        )

    private fun SessionState.plan(): CheckPlan = assertIs<SessionState.Active>(this).session.checkRun.plan

    @Test
    fun `the first ring runs the locked plan only while locked`() {
        val locked = marking.reduce(SessionState.Idle, alarmFired(testConfig(checkPlan = TWO_STEPS)), T0, userLocked = true).state
        val unlocked = marking.reduce(SessionState.Idle, alarmFired(testConfig(checkPlan = TWO_STEPS)), T0, userLocked = false).state

        assertEquals(LOCKED, locked.plan())
        assertEquals(TWO_STEPS, unlocked.plan())
    }

    @Test
    fun `a ring restored while locked runs the locked plan from the same step, restored unlocked it keeps its plan`() {
        val midCheck = ringSession(testConfig(checkPlan = TWO_STEPS)).let { it.copy(checkRun = it.checkRun.copy(step = StepPointer(1, 0))) }

        val locked = marking.reduce(SessionState.Loud(midCheck), SessionEvent.ProcessRestored, T0, userLocked = true).state
        val unlocked = marking.reduce(SessionState.Loud(midCheck), SessionEvent.ProcessRestored, T0, userLocked = false).state

        assertEquals(LOCKED, locked.plan())
        assertEquals(StepPointer(1, 0), assertIs<SessionState.Loud>(locked).session.checkRun.step)
        assertEquals(TWO_STEPS, unlocked.plan())
    }

    @Test
    fun `the ring after a snooze runs the locked plan while locked and the chosen plan after the unlock`() {
        val snoozed = snoozedSession().copy(config = testConfig(checkPlan = TWO_STEPS))
        val wasLocked = snoozed.copy(checkRun = snoozed.checkRun.copy(plan = LOCKED), beforeFirstUnlock = true)

        val locked = marking.reduce(SessionState.Snoozed(snoozed), SessionEvent.SlotFired, at(10.minutes), userLocked = true).state
        val unlocked = marking.reduce(SessionState.Snoozed(wasLocked), SessionEvent.SlotFired, at(10.minutes), userLocked = false).state

        assertEquals(LOCKED, locked.plan())
        assertEquals(TWO_STEPS, unlocked.plan(), "the chosen check is back")
        val fallback = snoozed.copy(checkRun = snoozed.checkRun.copy(plan = FALLBACK_PLAN, fallbackUsed = true))
        val ring2 = marking.reduce(SessionState.Snoozed(fallback), SessionEvent.SlotFired, at(10.minutes), userLocked = false).state
        assertEquals(FALLBACK_PLAN, ring2.plan(), "a used fallback stays")
        val ring2Run = assertIs<SessionState.Active>(ring2).session.checkRun
        assertTrue(ring2Run.fallbackUsed)
        assertEquals(ringSeeds(2, 3, fallback = true), ring2Run.seeds)
    }

    @Test
    fun `a fallback taken while locked comes back unsubstituted on the ring after the unlock (Story 3_1 review)`() {
        val grace = checkStates(ringSession()).first()
        val lockedGrace = grace.with(grace.session.copy(beforeFirstUnlock = true, directBootRing = true))
        val lockedFallback = assertIs<SessionState.Active>(marking.reduce(lockedGrace, SessionEvent.FallbackRequested, T0).state).session
        assertEquals(LOCKED to FALLBACK_PLAN, lockedFallback.checkRun.plan to lockedFallback.checkRun.fallbackSource)
        val snoozed = snoozedSession().copy(checkRun = lockedFallback.checkRun.restart(), beforeFirstUnlock = true)

        val unlocked = marking.reduce(SessionState.Snoozed(snoozed), SessionEvent.SlotFired, at(10.minutes), userLocked = false).state
        val stillLocked = marking.reduce(SessionState.Snoozed(snoozed), SessionEvent.SlotFired, at(10.minutes), userLocked = true).state

        assertEquals(FALLBACK_PLAN, unlocked.plan(), "the locked substitutes are not kept")
        assertEquals(LOCKED, stillLocked.plan())
        assertEquals(FALLBACK_PLAN, assertIs<SessionState.Active>(unlocked).session.checkRun.fallbackSource)
    }

    @Test
    fun `the fallback plan gets the substitutions before the first unlock`() {
        val grace = checkStates(ringSession()).first()

        val locked =
            marking
                .reduce(
                    grace.with(grace.session.copy(beforeFirstUnlock = true, directBootRing = true)),
                    SessionEvent.FallbackRequested,
                    T0,
                ).state
        val unlocked = marking.reduce(grace, SessionEvent.FallbackRequested, T0).state

        assertEquals(LOCKED, locked.plan())
        assertEquals(FALLBACK_PLAN, unlocked.plan())
    }

    @Test
    fun `the fallback follows the ring's Direct Boot flag, also after an unlock earlier in the ring (Story 2_4 review)`() {
        val grace = checkStates(ringSession()).first()
        // The ring started locked and saw the unlock: its sound stays the default one, so its check stays locked too.
        val unlockedInRing = grace.with(grace.session.copy(beforeFirstUnlock = false, directBootRing = true))

        assertEquals(LOCKED, marking.reduce(unlockedInRing, SessionEvent.FallbackRequested, T0).state.plan())
    }

    @Test
    fun `the ring after a snooze while still locked keeps the Direct Boot sound and check (Story 2_4 review)`() =
        runTest {
            val config = testConfig().copy(soundRef = systemSound)
            val snoozed =
                snoozedSession().copy(
                    config = config,
                    beforeFirstUnlock = true,
                    directBootRing = true,
                    startedBeforeUnlock = true,
                )
            store.commit(SessionState.Snoozed(snoozed))
            val engine = engine()
            engine.restore()
            time.advanceBy(9.minutes)

            val next = engine.dispatch(SessionEvent.SlotFired).session()

            assertEquals(2, next.ringIndex)
            assertTrue(next.beforeFirstUnlock)
            assertTrue(next.directBootRing)
            assertEquals(EntryEffect.SoundAt(Alarm.DEFAULT_SOUND_REF, 80), runner.entry.last { it is EntryEffect.SoundAt })
        }

    @Test
    fun `snooze availability - test mode first, then before the first unlock while locked, then prices not loaded`() {
        val policy = NoBillingSnoozeAvailability(lock)

        assertEquals(
            SnoozeAvailability.Unavailable(UnavailableReason.TestMode),
            policy.availability(ringSession(testConfig(testMode = true))),
        )
        assertEquals(SnoozeAvailability.Unavailable(UnavailableReason.BeforeFirstUnlock), policy.availability(ringSession()))
        lock.state.value = true
        assertEquals(SnoozeAvailability.Unavailable(UnavailableReason.CatalogueNotLoaded), policy.availability(ringSession()))
    }

    @Test
    fun `history says direct_boot for a locked first ring and for a ring only restored while locked, and not otherwise`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired())
            engine.dispatch(SessionEvent.ImUpTapped)
            engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))
            assertEquals(true, history.rows.getValue(SESSION_ID).directBoot, "first ring locked")

            lock.state.value = true
            val unlockedSession = "session-unlocked"
            val other = engine()
            store.row = null
            other.dispatch(SessionEvent.AlarmFired(unlockedSession, testConfig(), beforeFirstUnlock = false))
            other.dispatch(SessionEvent.ImUpTapped)
            other.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))
            assertEquals(false, history.rows.getValue(unlockedSession).directBoot, "never locked")

            val restoredSession = "session-restored"
            store.commit(SessionState.Ringing(ringSession().copy(sessionId = restoredSession)))
            lock.state.value = false
            time.reboot(off = 5.minutes)
            val afterReboot = engine()
            afterReboot.restore()
            afterReboot.dispatch(SessionEvent.ImUpTapped)
            afterReboot.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))
            assertEquals(true, history.rows.getValue(restoredSession).directBoot, "only the restored ring was locked")
        }

    @Test
    fun `an unlock while ringing lifts the lock flag but the substituted sound stays for this ring (Story 2_4)`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired(testConfig().copy(soundRef = systemSound)))
            lock.state.value = true
            runner.ran.clear()

            val after = engine.dispatch(SessionEvent.UserUnlocked).session()

            assertFalse(after.beforeFirstUnlock)
            assertTrue(after.directBootRing, "the current ring keeps the default sound")
            assertTrue(after.startedBeforeUnlock, "history keeps direct_boot")
            assertEquals(listOf(SessionEffect.LiftDirectBootSubstitutions, SessionEffect.InitBilling), runner.oneShot)
            assertEquals(EntryEffect.SoundAt(Alarm.DEFAULT_SOUND_REF, 80), runner.entry.first())
        }

    @Test
    fun `after the unlock the next ring (after a snooze) plays the chosen sound`() =
        runTest {
            val config = testConfig().copy(soundRef = systemSound)
            val snoozed =
                snoozedSession().copy(
                    config = config,
                    beforeFirstUnlock = true,
                    directBootRing = true,
                    startedBeforeUnlock = true,
                )
            store.commit(SessionState.Snoozed(snoozed))
            lock.state.value = true
            val engine = engine()
            engine.restore()
            time.advanceBy(9.minutes)

            val next = engine.dispatch(SessionEvent.SlotFired).session()

            assertEquals(2, next.ringIndex)
            assertFalse(next.beforeFirstUnlock, "the live lock state, so the unlock is not waited for again")
            assertFalse(next.directBootRing)
            assertEquals(config.checkPlan, next.checkRun.plan, "the chosen check is back with the chosen sound")
            assertEquals(EntryEffect.SoundAt(systemSound, 80), runner.entry.last { it is EntryEffect.SoundAt })
        }

    @Test
    fun `an unlock in Grace has no AD-2 row - ignored and logged, nothing changes`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired())
            val grace =
                assertIs<SessionState.Grace>(assertIs<Outcome.Success<SessionState>>(engine.dispatch(SessionEvent.ImUpTapped)).value)
            lock.state.value = true
            runner.ran.clear()

            assertEquals(grace, assertIs<Outcome.Success<SessionState>>(engine.dispatch(SessionEvent.UserUnlocked)).value)
            assertEquals(listOf<SessionEffect>(SessionEffect.LogIgnored("UserUnlocked", SESSION_ID)), runner.oneShot)
        }

    @Test
    fun `the unlocked default port says unlocked`() =
        runTest {
            assertTrue(UserLockState.Unlocked.isUserUnlocked())
            assertTrue(UserLockState.Unlocked.observe().first())
        }

    private companion object {
        /** The locked plan of [marking]: four steps, so it differs from every plan the tests start from. */
        val LOCKED = CheckPlan(CheckMode.All, List(4) { CheckPlan.PLACEHOLDER_ENTRY })
    }
}

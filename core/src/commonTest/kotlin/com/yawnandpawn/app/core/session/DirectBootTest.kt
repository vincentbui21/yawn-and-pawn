package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.alarm.Alarm
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

    private fun alarmFired(config: SessionConfig = testConfig()) =
        SessionEvent.AlarmFired(SESSION_ID, config, SEEDS, beforeFirstUnlock = false)

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
        assertTrue(CheckStep.Placeholder.isDirectBootSafe)
        assertEquals(CheckStep.Placeholder, DirectBootSubstitution.DIRECT_BOOT_CHECK)
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
            val test = engine().dispatch(SessionEvent.TestAlarmFired(SESSION_ID, testConfig(), SEEDS, beforeFirstUnlock = false)).session()
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
    fun `restored unlocked, a session keeps its flags as stored`() =
        runTest {
            lock.state.value = true
            store.commit(SessionState.Ringing(ringSession()))

            assertFalse(engine().restore().session().beforeFirstUnlock)
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
            other.dispatch(SessionEvent.AlarmFired(unlockedSession, testConfig(), SEEDS, beforeFirstUnlock = false))
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
    fun `the unlocked default port says unlocked`() =
        runTest {
            assertTrue(UserLockState.Unlocked.isUserUnlocked())
            assertTrue(UserLockState.Unlocked.observe().first())
        }
}

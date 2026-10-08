package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Story 4.8: the purchase intent is written ahead with the state, and the S1 unlock step runs through the engine. */
class SessionEnginePurchaseIntentTest {
    private val time = EngineTime()
    private val store = InMemorySessionStore()
    private val runner = RecordingRunner()
    private val logger = EngineLogger()
    private val history = InMemoryHistory()
    private val keyguard = CountingUnlockPort()

    private fun engine(
        effects: EffectRunner = runner,
        availability: SnoozeAvailability = SnoozeAvailability.Available(OFFER),
    ) = SessionEngine(
        reducer(availability = availability),
        store,
        effects,
        SessionRecorder(history),
        time.clock,
        time.monotonicClock,
        time.bootCounter,
        logger,
        unlock = keyguard,
    )

    private val alarmFired = SessionEvent.AlarmFired(SESSION_ID, testConfig(), beforeFirstUnlock = false)

    private fun Outcome<SessionState, DomainError>.session(): SessionData =
        assertIs<SessionState.Active>(assertIs<Outcome.Success<SessionState>>(this).value).session

    /** A keyguard whose state the test sets; counts every read, so a test sees which steps asked. */
    private class CountingUnlockPort : UnlockPort {
        var locked = false
        var reads = 0

        override fun isKeyguardLocked(): Boolean {
            reads++
            return locked
        }

        override suspend fun requestUnlock(): UnlockResult = UnlockResult.Succeeded
    }

    @Test
    fun `a Pay commits the paying state and the intent in one commit, then launches billing, and the runner never persists`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired)
            time.advanceBy(1.minutes)
            var effectsAtCommit = -1
            store.onCommit = { effectsAtCommit = runner.ran.size }
            runner.ran.clear()

            val session = engine.dispatch(PAY).session()

            assertEquals(INTENT, session.paying)
            assertEquals(0, effectsAtCommit, "nothing ran before the commit")
            assertEquals(listOf(RuntimeWrite.PutPurchaseIntent(intentAt(time.now))), store.writeLog.last(), "intent in the Pay's commit")
            assertEquals(intentAt(time.now), store.intents.getValue(INTENT))
            assertEquals(listOf<SessionEffect>(SessionEffect.LaunchBilling(INTENT, SESSION_ID)), runner.oneShot)
        }

    @Test
    fun `a failing commit writes no intent, launches nothing and keeps the previous state`() =
        runTest {
            val engine = engine()
            val ringing = assertIs<Outcome.Success<SessionState>>(engine.dispatch(alarmFired)).value
            store.commitFailure = DomainError.StorageFailure("disk full")
            runner.ran.clear()

            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk full")), engine.dispatch(PAY))

            assertTrue(store.intents.isEmpty(), "no intent row")
            assertTrue(runner.ran.isEmpty(), "no launch, no effect")
            assertEquals(ringing, engine.state.value)
            assertEquals(ringing, store.stored)
            assertEquals(LogEvent.OperationFailed("commit session state", "storage failure: disk full"), logger.events.last())

            // The next Pay, once storage works, goes through.
            store.commitFailure = null
            assertEquals(INTENT, engine.dispatch(PAY).session().paying)
            assertEquals(setOf(INTENT), store.intents.keys)
        }

    @Test
    fun `a second Pay while paying writes no second intent and launches nothing, and is logged`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired)
            engine.dispatch(PAY)
            runner.ran.clear()

            engine.dispatch(SessionEvent.PayConfirmed(PurchaseIntentId("intent-2"), QUOTE))

            assertEquals(setOf(INTENT), store.intents.keys)
            assertFalse(runner.ran.any { it is SessionEffect.LaunchBilling })
            assertTrue(SessionEffect.LogIgnored("PayConfirmed", SESSION_ID) in runner.ran)
        }

    @Test
    fun `a Pay while snooze is unavailable is ignored and logged, and writes nothing`() =
        runTest {
            val engine = engine(availability = SnoozeAvailability.Unavailable(UnavailableReason.Offline))
            engine.dispatch(alarmFired)
            runner.ran.clear()

            assertNull(engine.dispatch(PAY).session().paying)

            assertTrue(store.intents.isEmpty())
            assertTrue(store.writeLog.all { it.isEmpty() })
            assertEquals(listOf<SessionEffect>(SessionEffect.LogIgnored("PayConfirmed", SESSION_ID)), runner.oneShot)
        }

    @Test
    fun `a locked Pay asks for the unlock, and billing launches only after UnlockSucceeded`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired)
            keyguard.locked = true
            runner.ran.clear()

            val unlocking = engine.dispatch(PAY).session()

            assertEquals(INTENT, unlocking.paying)
            assertTrue(unlocking.unlocking)
            assertEquals(setOf(INTENT), store.intents.keys, "the intent is written before the PIN prompt")
            assertEquals(listOf<SessionEffect>(SessionEffect.RequestKeyguardDismiss(INTENT)), runner.oneShot)

            runner.ran.clear()
            val paying = engine.dispatch(SessionEvent.UnlockSucceeded).session()

            assertFalse(paying.unlocking)
            assertEquals(INTENT, paying.paying)
            assertEquals(listOf<SessionEffect>(SessionEffect.LaunchBilling(INTENT, SESSION_ID)), runner.oneShot)
            assertEquals(1, store.intents.size, "the unlock writes no second intent")
        }

    @Test
    fun `a cancelled unlock clears the payment with no charge and the alarm keeps ringing`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired)
            keyguard.locked = true
            engine.dispatch(PAY)
            runner.ran.clear()

            val after = engine.dispatch(SessionEvent.UnlockFailed)

            val session = after.session()
            assertNull(session.paying)
            assertFalse(session.unlocking)
            assertIs<SessionState.Ringing>(engine.state.value)
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.UnlockFailed)), runner.oneShot)
            assertTrue(runner.entry.any { it is EntryEffect.SoundAt }, "the sound is still wanted")
            assertFalse(runner.ran.any { it is SessionEffect.LaunchBilling || it == SessionEffect.StopSound })
        }

    @Test
    fun `the keyguard is read only for a Pay, and a port that cannot tell counts as locked`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired)
            engine.dispatch(SessionEvent.SnoozeTapped)
            engine.dispatch(SessionEvent.UserInteracted)
            assertEquals(0, keyguard.reads, "no platform call for other events")

            engine.dispatch(PAY)
            assertEquals(1, keyguard.reads)

            val throwing =
                object : UnlockPort {
                    override fun isKeyguardLocked(): Boolean = error("keyguard service gone")

                    override suspend fun requestUnlock(): UnlockResult = UnlockResult.Failed
                }
            val other = InMemorySessionStore()
            val second =
                SessionEngine(
                    reducer(),
                    other,
                    runner,
                    SessionRecorder(InMemoryHistory()),
                    time.clock,
                    time.monotonicClock,
                    time.bootCounter,
                    logger,
                    unlock = throwing,
                )
            second.dispatch(alarmFired)
            assertTrue(second.dispatch(PAY).session().unlocking, "unsure means ask for the unlock")
        }

    @Test
    fun `a session killed while unlocking is restored ringing, with paying and unlocking cleared and the intent kept`() =
        runTest {
            val crashing = RecordingRunner().apply { hangOn = { it is SessionEffect.RequestKeyguardDismiss } }
            val first = engine(effects = crashing)
            first.dispatch(alarmFired)
            keyguard.locked = true
            backgroundScope.launch { first.dispatch(PAY) }
            runCurrent()
            assertTrue(assertIs<SessionState.Ringing>(store.stored).session.unlocking, "committed before its effects")

            val second = engine()
            val restored = second.restore().session()

            assertNull(restored.paying)
            assertFalse(restored.unlocking)
            assertEquals(setOf(INTENT), store.intents.keys)
            assertFalse(runner.ran.any { it is SessionEffect.LaunchBilling || it is SessionEffect.RequestKeyguardDismiss })

            // A late unlock result for the dead process is ignored: nothing launches.
            runner.ran.clear()
            second.dispatch(SessionEvent.UnlockSucceeded)
            assertEquals(listOf<SessionEffect>(SessionEffect.LogIgnored("UnlockSucceeded", SESSION_ID)), runner.oneShot)
        }

    @Test
    fun `I'm up and a passed check work while the unlock is pending, and a late unlock result then launches nothing`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired)
            keyguard.locked = true
            engine.dispatch(PAY)

            assertIs<SessionState.Grace>(assertIs<Outcome.Success<SessionState>>(engine.dispatch(SessionEvent.ImUpTapped)).value)
            engine.dispatch(SessionEvent.CheckAnswerSubmitted(com.yawnandpawn.app.core.checks.CheckAnswer.Placeholder))
            assertTrue(history.rows.getValue(SESSION_ID).outcome != null, "the session completed and was recorded")
            runner.ran.clear()

            engine.dispatch(SessionEvent.UnlockSucceeded)

            assertFalse(runner.ran.any { it is SessionEffect.LaunchBilling })
        }
}

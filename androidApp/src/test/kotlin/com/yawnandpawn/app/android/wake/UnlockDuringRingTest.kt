package com.yawnandpawn.app.android.wake

import android.app.AlarmManager
import android.content.Intent
import android.os.Looper
import android.os.UserManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.lifecycle.Lifecycle
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.crash.FirebaseStartup
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.billing.PurchaseIntent
import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.billing.hash
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.RuntimeWrite
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.data.session.ActiveSessionDao
import com.yawnandpawn.app.data.session.GrantLedgerEntity
import com.yawnandpawn.app.launchActivity
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.FakeBilling
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeUserLockState
import com.yawnandpawn.app.testing.aGrant
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Story 2.4: an unlock during a ring before the first unlock after a reboot. The wake service listens for it while such
 * a session runs, the AD-2 row (or the logged ignore) follows, billing and crash reporting start outside the table, and
 * the wake screen's snooze control changes in place.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class UnlockDuringRingTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val billing = FakeBilling()
    private val scheduledAt = Instant.parse("2027-03-08T06:00:00Z")

    private fun userManager(app: WakeApp) = shadowOf(app.app.getSystemService(UserManager::class.java))

    private fun unlockReceivers(app: WakeApp): Int =
        shadowOf(app.app).registeredReceivers.count { it.intentFilter.hasAction(Intent.ACTION_USER_UNLOCKED) }

    /** A ring that starts while the phone is still locked after a reboot. */
    private fun lockedRing(app: WakeApp): ServiceController<WakeService> {
        userManager(app).setUserUnlocked(false)
        upsert(app, "alarm-a", 1000)
        val service = app.ring(AlarmFired("alarm-a", scheduledAt))
        app.awaitRinging()
        assertTrue((app.engine.state.value as SessionState.Ringing).session.beforeFirstUnlock)
        return service
    }

    private fun upsert(
        app: WakeApp,
        id: String,
        requestCode: Int,
    ) = assertEquals(
        Outcome.Success(Unit),
        runBlocking { app.koin.get<AlarmRepository>().upsert(anAlarm(id = id, requestCode = requestCode)) },
    )

    /** An [UnlockSignals] of [app]'s engine with its own [billing] and [logger], retrying at once. */
    private fun signals(
        app: WakeApp,
        logger: FakeLogger = FakeLogger(),
        billing: Billing = this.billing,
        firebase: FirebaseStartup = app.koin.get(),
    ) = UnlockSignals(app.engine, billing, firebase, app.koin.get(), logger, retry = Duration.ZERO)

    /** A session that rings before the first unlock, dispatched straight to the engine (no service). */
    private fun lockedSession(app: WakeApp) {
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(), beforeFirstUnlock = true))
        assertTrue(assertIs<SessionState.Ringing>(app.engine.state.value).session.beforeFirstUnlock)
    }

    private fun ignoredUnlocks(app: WakeApp): Int = app.logs().count { it.startsWith("SessionEventIgnored type=UserUnlocked") }

    /** The user unlocks: the system flips the lock state and sends `ACTION_USER_UNLOCKED` to registered receivers. */
    private fun unlock(app: WakeApp) {
        userManager(app).setUserUnlocked(true)
        app.app.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        shadowOf(Looper.getMainLooper()).idle()
        app.koin.get<ApplicationScope>().awaitChildren()
    }

    @Test
    fun `the service listens for the unlock while the locked session runs, applies the AD-2 row once and stops listening at the end`() {
        val app = WakeApp(billing = billing)
        lockedRing(app)
        app.awaitUntil("a context-registered ACTION_USER_UNLOCKED receiver (${unlockReceivers(app)})") { unlockReceivers(app) == 1 }

        unlock(app)
        app.awaitUntil("the unlock is applied") { (app.engine.state.value as? SessionState.Ringing)?.session?.beforeFirstUnlock == false }

        val ringing = app.engine.state.value as SessionState.Ringing
        assertTrue(ringing.session.directBootRing, "the current ring keeps the Direct Boot sound")
        assertEquals(1, billing.initCalls, "billing initialised once (AD-2 InitBilling and the signal share it)")
        assertTrue(app.logs().any { it == "SessionEffectLogged type=LiftDirectBootSubstitutions entry=false" }, "${app.logs()}")

        app.dispatch(SessionEvent.ImUpTapped)
        app.solveCheck()
        app.awaitUntil("the session ends") { app.engine.state.value == SessionState.Idle }
        app.awaitUntil("the receiver is unregistered") { unlockReceivers(app) == 0 }
    }

    @Test
    fun `an unlock in Grace is ignored and logged by AD-2 but billing still initialises`() {
        val app = WakeApp(billing = billing)
        lockedRing(app)
        app.dispatch(SessionEvent.ImUpTapped)
        assertIs<SessionState.Grace>(app.engine.state.value)

        unlock(app)
        app.awaitUntil("billing initialised") { billing.initCalls == 1 }
        app.awaitUntil("the ignore is logged") { app.logs().any { it.startsWith("SessionEventIgnored type=UserUnlocked") } }

        assertIs<SessionState.Grace>(app.engine.state.value)
    }

    @Test
    fun `repeated unlock signals - the broadcast, BOOT_COMPLETED and the resumed screen - make one unlock and never throw`() {
        val app = WakeApp(billing = billing)
        lockedRing(app)
        unlock(app)
        app.awaitUntil("the unlock is applied") { (app.engine.state.value as? SessionState.Ringing)?.session?.beforeFirstUnlock == false }
        val after = app.engine.state.value

        app.koin.get<UnlockSignals>().onUnlocked()
        app.app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(app.app.packageName))
        shadowOf(Looper.getMainLooper()).idle()
        app.koin.get<ApplicationScope>().awaitChildren()

        assertEquals(after, app.engine.state.value)
        assertEquals(1, billing.initCalls)
    }

    @Test
    fun `every unlock signal replays the grant ledger, the first unlock and each wake screen resume alike (Story 4-10)`() {
        val app = WakeApp(billing = billing)
        var replays = 0
        val replay: () -> Unit = { replays++ }
        val signals = UnlockSignals(app.engine, billing, app.koin.get(), app.koin.get(), FakeLogger(), Duration.ZERO, replay)

        signals.onUnlocked()
        signals.onScreenResumedUnlocked()
        signals.initialiseAfterUnlock()

        assertEquals(3, replays)
        assertEquals(1, billing.initCalls, "billing still starts once")
    }

    @Test
    fun `the app's unlock signal settles a payment left in the grant ledger by an overnight restart (Story 4-10)`() {
        val app = WakeApp(billing = billing)
        val grant = aGrant(token = "left-overnight")
        runBlocking { app.koin.get<ActiveSessionDao>().commit(null, grants = listOf(GrantLedgerEntity.of(grant))) }

        app.koin.get<UnlockSignals>().onUnlocked()
        shadowOf(Looper.getMainLooper()).idle()
        app.koin.get<ApplicationScope>().awaitChildren()

        val record = runBlocking { app.koin.get<PurchaseRecordRepository>().get(grant.token.hash()) }
        assertEquals(RecordStatus.Consumed, assertIs<Outcome.Success<PurchaseRecord?>>(record).value?.status)
        assertEquals(listOf(grant.token), billing.consumed)
    }

    @Test
    fun `BOOT_COMPLETED after the unlock is an unlock signal for a ring that missed the broadcast`() {
        val app = WakeApp(billing = billing)
        lockedRing(app)
        userManager(app).setUserUnlocked(true)

        app.app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(app.app.packageName))
        shadowOf(Looper.getMainLooper()).idle()
        app.awaitUntil("the unlock is applied") { (app.engine.state.value as? SessionState.Ringing)?.session?.beforeFirstUnlock == false }

        assertEquals(1, billing.initCalls)
    }

    @Test
    fun `unlocking while the wake screen is in front changes the snooze in place, without finishing or recreating the screen`() {
        val lock = FakeUserLockState(unlocked = false)
        val app = WakeApp(billing = billing, userLock = lock)
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(), beforeFirstUnlock = true))
        val scenario = launchActivity<WakeActivity>(Intent(app.app, WakeActivity::class.java))
        composeRule.onNodeWithContentDescription("Snooze unavailable, Unlock your phone to snooze").assertExists()
        var before: WakeActivity? = null
        scenario.onActivity { before = it }

        // The PIN prompt of requestDismissKeyguard (Spike S1) or the lock screen: the screen stays resumed on top.
        lock.unlock()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Snooze unavailable, prices not loaded yet").assertExists()
        scenario.onActivity { now ->
            assertSame(before, now, "the same activity instance, not recreated")
            assertFalse(now.isFinishing)
        }
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
    }

    @Test
    fun `the wake screen resumed with the user unlocked is an unlock signal`() {
        val lock = FakeUserLockState(unlocked = false)
        val app = WakeApp(billing = billing, userLock = lock)
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(), beforeFirstUnlock = true))
        val scenario = launchActivity<WakeActivity>(Intent(app.app, WakeActivity::class.java))
        lock.unlock()

        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        app.awaitUntil("the unlock is applied") { (app.engine.state.value as? SessionState.Ringing)?.session?.beforeFirstUnlock == false }

        assertEquals(1, billing.initCalls)
    }

    // Story 2.4 review fixes.

    @Test
    fun `a locked ring that ends without an unlock stops listening, and the next locked ring in the same service listens again`() {
        val app = WakeApp(billing = billing)
        val service = lockedRing(app)
        app.awaitUntil("a receiver for the first ring") { unlockReceivers(app) == 1 }

        app.dispatch(SessionEvent.ImUpTapped)
        app.solveCheck()
        app.awaitUntil("the session ends") { app.engine.state.value == SessionState.Idle }
        app.awaitUntil("the receiver is unregistered (${unlockReceivers(app)})") { unlockReceivers(app) == 0 }

        upsert(app, "alarm-b", 1001)
        service.withIntent(WakeService.alarmIntent(app.app, AlarmFired("alarm-b", scheduledAt))).startCommand(0, 2)
        app.awaitUntil(
            "the second locked ring",
        ) { (app.engine.state.value as? SessionState.Ringing)?.session?.config?.alarmId == "alarm-b" }
        assertTrue(assertIs<SessionState.Ringing>(app.engine.state.value).session.beforeFirstUnlock)
        app.awaitUntil("a fresh receiver (${unlockReceivers(app)})") { unlockReceivers(app) == 1 }

        // Not unlock(app): background work of the second start may need the main looper, so wait without blocking it.
        userManager(app).setUserUnlocked(true)
        app.app.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        app.awaitUntil("the unlock is applied") { (app.engine.state.value as? SessionState.Ringing)?.session?.beforeFirstUnlock == false }
    }

    @Test
    fun `an unlock seen during a snooze is not watched again for that ring, but a later ring still before the unlock is`() {
        val lock = FakeUserLockState(unlocked = false)
        val app = WakeApp(billing = billing, userLock = lock)
        val snoozed =
            SessionState.Snoozed(
                aSession().copy(
                    interactionDeadline = null,
                    snoozesGranted = 1,
                    snoozeEnd = Deadline.after(app.now(), 9.minutes),
                    beforeFirstUnlock = true,
                    directBootRing = true,
                    startedBeforeUnlock = true,
                ),
            )
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<ActiveSessionStore>().commit(snoozed) })
        app.startService(WakeService.intent(app.app, WakeService.ACTION_RESTORE))
        app.awaitUntil("the snooze is restored") { app.engine.state.value is SessionState.Snoozed }

        lock.unlock()
        app.awaitUntil("Snoozed ignores the unlock (AD-2)") { ignoredUnlocks(app) == 1 }
        assertEquals(1, billing.initCalls)
        assertTrue(assertIs<SessionState.Snoozed>(app.engine.state.value).session.beforeFirstUnlock)

        // A next ring that still says before the first unlock (the port says locked again here): a fresh watch serves it.
        lock.unlocked = false
        app.dispatch(SessionEvent.OverlapAlarmFired("alarm-b", scheduledAt))
        val next = assertIs<SessionState.Ringing>(app.engine.state.value).session
        assertEquals(2, next.ringIndex)
        assertTrue(next.beforeFirstUnlock)
        lock.unlock()

        app.awaitUntil("the new ring's watch applies the unlock") {
            (app.engine.state.value as? SessionState.Ringing)?.session?.beforeFirstUnlock ==
                false
        }
        assertEquals(1, ignoredUnlocks(app), "the watch of the snoozed ring did not fire again")
    }

    @Test
    fun `a billing or Firebase start that throws is logged, never thrown, and tried again on the next signal`() {
        val app = WakeApp()
        val logger = FakeLogger()
        var failing = true
        var billingStarts = 0
        var firebaseStarts = 0
        val flakyBilling =
            object : Billing {
                override suspend fun launch(intent: PurchaseIntent): SessionEvent.PurchaseEvent = error("not launched here")

                override fun init() {
                    billingStarts++
                    check(!failing) { "Play not connected" }
                }
            }
        val firebase =
            FirebaseStartup(app.app, logger, configured = { true }, unlocked = { true }, initialize = {
                firebaseStarts++
                check(!failing) { "no Firebase" }
            })
        val signals = signals(app, logger, flakyBilling, firebase)

        signals.onUnlocked()
        assertEquals(
            listOf<LogEvent>(
                LogEvent.OperationFailed("start billing", "IllegalStateException"),
                LogEvent.OperationFailed("start crash reporting", "IllegalStateException"),
            ),
            logger.events,
        )

        failing = false
        signals.onUnlocked()
        signals.initialiseAfterUnlock()

        assertEquals(2, billingStarts, "tried again once, then done")
        assertEquals(2, firebaseStarts)
        assertTrue(firebase.started)
    }

    @Test
    fun `a UserUnlocked whose commit failed is sent again while the ring waits for the unlock, a bounded number of times`() {
        val store = FlakyCommitStore()
        val app = WakeApp(billing = billing, store = store)
        lockedSession(app)
        val logger = FakeLogger()

        store.failures.addAndGet(2)
        val before = store.attempts.get()
        signals(app, logger).onUnlocked()
        app.awaitUntil("applied on the third attempt") {
            (app.engine.state.value as? SessionState.Ringing)?.session?.beforeFirstUnlock ==
                false
        }
        assertEquals(3, store.attempts.get() - before)
        assertEquals(emptyList(), logger.events)
    }

    @Test
    fun `a UserUnlocked that is never committed gives up after MAX_ATTEMPTS and logs it`() {
        val store = FlakyCommitStore()
        val app = WakeApp(billing = billing, store = store)
        lockedSession(app)
        val logger = FakeLogger()

        store.failures.addAndGet(Int.MAX_VALUE)
        val before = store.attempts.get()
        signals(app, logger).onUnlocked()
        app.awaitUntil("the retries end") { logger.events.isNotEmpty() }

        assertEquals(UnlockSignals.MAX_ATTEMPTS, store.attempts.get() - before)
        assertEquals(
            listOf<LogEvent>(
                LogEvent.OperationFailed("apply the unlock", "UserUnlocked not committed after ${UnlockSignals.MAX_ATTEMPTS} attempts"),
            ),
            logger.events,
        )
        assertTrue(assertIs<SessionState.Ringing>(app.engine.state.value).session.beforeFirstUnlock)
    }

    @Test
    fun `signals that arrive while a UserUnlocked dispatch runs dispatch nothing more`() {
        val store = FlakyCommitStore()
        val app = WakeApp(billing = billing, store = store)
        lockedSession(app)
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // The unlocked ring's commit waits: the engine still shows the ring before the unlock meanwhile.
        store.beforeCommit = { state ->
            if (state is SessionState.Ringing && !state.session.beforeFirstUnlock) {
                reached.complete(Unit)
                release.await()
            }
        }
        val signals = signals(app)

        signals.onUnlocked()
        runBlocking { withTimeout(30.seconds) { reached.await() } }
        assertTrue(assertIs<SessionState.Ringing>(app.engine.state.value).session.beforeFirstUnlock)
        signals.onUnlocked()
        signals.onScreenResumedUnlocked()
        release.complete(Unit)

        app.awaitUntil("the unlock is applied") { (app.engine.state.value as? SessionState.Ringing)?.session?.beforeFirstUnlock == false }
        app.koin.get<ApplicationScope>().awaitChildren()
        assertEquals(0, ignoredUnlocks(app), "no second UserUnlocked reached the engine")
    }

    @Test
    fun `the wake screen resumed unlocked during a snooze starts billing but sends no UserUnlocked`() {
        val lock = FakeUserLockState(unlocked = true)
        val app = WakeApp(billing = billing, userLock = lock)
        val snoozed =
            SessionState.Snoozed(
                aSession().copy(
                    interactionDeadline = null,
                    snoozesGranted = 1,
                    snoozeEnd = Deadline.after(app.now(), 9.minutes),
                    beforeFirstUnlock = true,
                    directBootRing = true,
                ),
            )
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<ActiveSessionStore>().commit(snoozed) })
        // The screen restores the session itself (a restore entry point).
        val scenario = launchActivity<WakeActivity>(Intent(app.app, WakeActivity::class.java))
        app.awaitUntil("the snooze is restored") { app.engine.state.value is SessionState.Snoozed }

        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        app.awaitUntil("billing initialised") { billing.initCalls == 1 }
        app.koin.get<ApplicationScope>().awaitChildren()

        assertEquals(0, ignoredUnlocks(app))
        assertIs<SessionState.Snoozed>(app.engine.state.value)
    }

    @Test
    fun `BOOT_COMPLETED re-arms the alarms even when the unlock signal's start throws`() {
        val throwing =
            object : Billing {
                override suspend fun launch(intent: PurchaseIntent): SessionEvent.PurchaseEvent = error("not launched here")

                override fun init() = throw IllegalStateException("Play not connected")
            }
        val app = WakeApp(billing = throwing)
        userManager(app).setUserUnlocked(true)
        lockedSession(app)
        // Stored only: nothing arms it but the re-arm.
        upsert(app, "alarm-a", 1000)
        val alarms = shadowOf(app.app.getSystemService(AlarmManager::class.java))
        val armedBefore = alarms.scheduledAlarms.size

        app.app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(app.app.packageName))
        shadowOf(Looper.getMainLooper()).idle()

        app.awaitUntil("the unlock is applied") { (app.engine.state.value as? SessionState.Ringing)?.session?.beforeFirstUnlock == false }
        app.awaitUntil("the alarm is armed again (${alarms.scheduledAlarms.size})") { alarms.scheduledAlarms.size == armedBefore + 1 }
        assertTrue(app.logs().any { it == "OperationFailed operation=start billing cause=IllegalStateException" }, "${app.logs()}")
    }
}

/** A session store whose next [failures] commits fail; it counts every [attempts] and can hold a commit ([beforeCommit]). */
private class FlakyCommitStore(
    private val inner: FakeActiveSessionStore = FakeActiveSessionStore(),
) : ActiveSessionStore by inner {
    val failures = AtomicInteger()
    val attempts = AtomicInteger()

    @Volatile
    var beforeCommit: suspend (SessionState) -> Unit = {}

    override suspend fun commit(
        state: SessionState,
        writes: List<RuntimeWrite>,
    ): Outcome<Unit, DomainError> {
        attempts.incrementAndGet()
        if (failures.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) return Outcome.Failure(DomainError.StorageFailure("disk full"))
        beforeCommit(state)
        return inner.commit(state, writes)
    }
}

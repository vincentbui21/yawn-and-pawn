package com.yawnandpawn.app.android.wake

import android.content.Intent
import android.os.Looper
import android.os.UserManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.CheckAnswer
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.FakeBilling
import com.yawnandpawn.app.testing.FakeUserLockState
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
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
    private fun lockedRing(app: WakeApp) {
        userManager(app).setUserUnlocked(false)
        assertEquals(
            Outcome.Success(Unit),
            runBlocking { app.koin.get<AlarmRepository>().upsert(anAlarm(id = "alarm-a", requestCode = 1000)) },
        )
        app.ring(AlarmFired("alarm-a", scheduledAt))
        app.awaitRinging()
        assertTrue((app.engine.state.value as SessionState.Ringing).session.beforeFirstUnlock)
    }

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

        app.dispatch(SessionEvent.ImUpTapped, SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))
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
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(), listOf(1L), beforeFirstUnlock = true))
        val scenario = ActivityScenario.launch<WakeActivity>(Intent(app.app, WakeActivity::class.java))
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
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(), listOf(1L), beforeFirstUnlock = true))
        val scenario = ActivityScenario.launch<WakeActivity>(Intent(app.app, WakeActivity::class.java))
        lock.unlock()

        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        app.awaitUntil("the unlock is applied") { (app.engine.state.value as? SessionState.Ringing)?.session?.beforeFirstUnlock == false }

        assertEquals(1, billing.initCalls)
    }
}

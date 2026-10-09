package com.yawnandpawn.app.android.wake

import android.app.Activity
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.billing.AndroidBilling
import com.yawnandpawn.app.android.billing.AndroidDeviceUnlocker
import com.yawnandpawn.app.android.billing.AndroidProductDetailsSource
import com.yawnandpawn.app.android.billing.BillingTimeouts
import com.yawnandpawn.app.android.billing.FakeBillingClientFacade
import com.yawnandpawn.app.android.billing.ResumedWakeScreen
import com.yawnandpawn.app.core.billing.ProductDetailsSource
import com.yawnandpawn.app.core.billing.PurchaseCoordinator
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.UnlockPort
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.testing.FakeSnoozeAvailability
import com.yawnandpawn.app.testing.FakeUserLockState
import com.yawnandpawn.app.testing.aPayConfirmed
import com.yawnandpawn.app.testing.aSessionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Story 4.12 in the app's own Koin graph: Play Billing replaces the stand-ins, and a Play that never connects holds up
 * nothing on the wake path (alarm safety).
 */
@RunWith(RobolectricTestRunner::class)
class PlayBillingWiringTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val billingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = billingScope.cancel()

    @Test
    fun `Koin binds Play Billing as the one Billing, the price source and the one unlock port`() {
        val app = WakeApp()
        val koin = app.koin

        val billing = assertIs<AndroidBilling>(koin.get<Billing>())
        assertSame(billing, koin.get<AndroidBilling>())
        assertIs<AndroidProductDetailsSource>(koin.get<ProductDetailsSource>())
        val unlock = assertIs<AndroidDeviceUnlocker>(koin.get<UnlockPort>())
        assertSame(unlock, koin.get<UnlockPort>())
        assertSame(app.app.resumedWakeScreen, koin.get<ResumedWakeScreen>())
    }

    @Test
    fun `a Play that never connects never holds up the Pay, I'm up, the check or the end of the session`() {
        val facade = FakeBillingClientFacade().apply { setupCode = null } // Play never answers
        facade.products["snooze_usd_01"] = FakeBillingClientFacade.product("snooze_usd_01")
        val screen = Robolectric.buildActivity(Activity::class.java).setup().get()
        val billing =
            AndroidBilling(
                facade = facade,
                userLock = UserLockState.Unlocked,
                wakeScreens = { screen },
                clock = FakeMonotonicClock(),
                logger = FakeLogger(),
                scope = billingScope,
                main = Dispatchers.Main,
                timeouts = BillingTimeouts(connect = 60.seconds, query = 60.seconds),
            )
        val app = WakeApp(billing = billing, policy = FakeSnoozeAvailability())
        app.koin.get<PurchaseCoordinator>()
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(), beforeFirstUnlock = false))
        app.awaitRinging()

        app.dispatch(SessionEvent.SnoozeTapped, aPayConfirmed())
        app.awaitUntil("the coordinator asked Play") { facade.startConnections >= 1 }
        val paying = assertIs<SessionState.Ringing>(app.engine.state.value).session.paying
        assertEquals("intent-1", paying?.value, "the payment waits for Play")
        assertTrue(app.player.sound != null, "the alarm still rings")

        app.dispatch(SessionEvent.ImUpTapped)
        app.solveCheck()

        assertTrue(app.engine.state.value !is SessionState.Ring, "the session ended while Play still had not answered")
        assertEquals(1, facade.startConnections, "one connection attempt, still waiting")
        // Play connects at last, with the product known and a wake screen resumed: the held coordinator goes on, finds the
        // session no longer pays, and opens nothing.
        facade.answerSetup(BillingResponseCode.OK)
        app.awaitUntil("the coordinator queried Play") { facade.purchaseQueries >= 1 }
        Thread.sleep(SETTLE_MILLIS)
        app.awaitUntil("idle") { true }
        assertTrue(facade.launches.isEmpty(), "no Play sheet for an ended session")
    }

    @Test
    fun `before the first unlock nothing connects to Play, and the unlock starts the connection`() {
        val facade = FakeBillingClientFacade(available = true)
        val userLock = FakeUserLockState(unlocked = false)
        val app = WakeApp(userLock = userLock, billingFacade = facade, policy = FakeSnoozeAvailability())
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(), beforeFirstUnlock = true))
        app.awaitRinging()
        val coordinator = app.koin.get<PurchaseCoordinator>()

        runBlocking { coordinator.recover() }
        assertIs<Outcome.Failure<DomainError>>(runBlocking { app.koin.get<Billing>().queryPurchases() })
        app.koin.get<UnlockSignals>().initialiseAfterUnlock()
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(0, facade.startConnections, "nothing connects before the first unlock (AD-15)")

        userLock.unlock()
        app.koin.get<UnlockSignals>().onUnlocked()

        app.awaitUntil("billing connects after the unlock") { facade.startConnections == 1 }
    }

    private companion object {
        const val SETTLE_MILLIS = 200L
    }
}

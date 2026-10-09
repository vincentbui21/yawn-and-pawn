package com.yawnandpawn.app.android.wake

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
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.UnlockPort
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.testing.FakeSnoozeAvailability
import com.yawnandpawn.app.testing.aPayConfirmed
import com.yawnandpawn.app.testing.aSessionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
        val billing =
            AndroidBilling(
                facade = facade,
                userLock = UserLockState.Unlocked,
                wakeScreens = { null },
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
        // Play answers at last (and lets the held coordinator finish): the payment is no longer wanted, nothing opens.
        facade.setupCode = BillingResponseCode.BILLING_UNAVAILABLE
        facade.answerSetup(BillingResponseCode.BILLING_UNAVAILABLE)
        assertTrue(facade.launches.isEmpty())
    }
}

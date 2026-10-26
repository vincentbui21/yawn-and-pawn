package com.yawnandpawn.app

import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.android.AndroidConnectivity
import com.yawnandpawn.app.android.AndroidUserLockState
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.billing.AndroidBilling
import com.yawnandpawn.app.android.billing.PlayBillingCountry
import com.yawnandpawn.app.android.billing.PlayLivePrices
import com.yawnandpawn.app.android.crash.FirebaseCrashReporter
import com.yawnandpawn.app.android.crash.isFirebaseConfigured
import com.yawnandpawn.app.android.wake.CatalogDisplayPrices
import com.yawnandpawn.app.android.wake.NoOpCrashReporter
import com.yawnandpawn.app.android.wake.SheetEffectRunner
import com.yawnandpawn.app.android.wake.WakeApp
import com.yawnandpawn.app.android.wake.WakeRuntime
import com.yawnandpawn.app.core.billing.BillingCountry
import com.yawnandpawn.app.core.billing.DisplayPrices
import com.yawnandpawn.app.core.billing.FeeLadder
import com.yawnandpawn.app.core.billing.LivePriceSource
import com.yawnandpawn.app.core.billing.LiveSnoozeAvailability
import com.yawnandpawn.app.core.billing.MoneyFormatter
import com.yawnandpawn.app.core.billing.SessionStartPriceRefresh
import com.yawnandpawn.app.core.billing.SnoozeConditions
import com.yawnandpawn.app.core.billing.UsdFeeLadder
import com.yawnandpawn.app.core.crash.CrashReporter
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.net.Connectivity
import com.yawnandpawn.app.core.reliability.ReliabilityRecords
import com.yawnandpawn.app.core.reliability.StoredReliability
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.CameraFallbackPolicy
import com.yawnandpawn.app.core.session.CheckValidator
import com.yawnandpawn.app.core.session.EffectRunner
import com.yawnandpawn.app.core.session.FallbackPolicy
import com.yawnandpawn.app.core.session.PluginCheckValidator
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionRecorder
import com.yawnandpawn.app.core.session.SessionReducer
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailabilityPolicy
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.stats.DayTicker
import com.yawnandpawn.app.core.stats.ObserveProgress
import com.yawnandpawn.app.data.dataModule
import com.yawnandpawn.app.data.history.RoomSessionHistoryRepository
import com.yawnandpawn.app.data.session.RoomActiveSessionStore
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.ui.format.AndroidMoneyFormatter
import com.yawnandpawn.app.ui.uiModule
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowKeyguardManager
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Stories 1.12 to 1.14: Koin binds one session engine over runtime.db and session history, with the Epic 1 policies, and
 * the wake runtime as its effect runner.
 */
@RunWith(RobolectricTestRunner::class)
class SessionWiringTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @Test
    fun `Koin binds one session engine, its reducer, the Room session store, the history recorder, the wake runtime and Play billing`() {
        val koin = GlobalContext.get()

        assertSame(koin.get<SessionEngine>(), koin.get<SessionEngine>())
        assertSame(koin.get<SessionReducer>(), koin.get<SessionReducer>())
        assertIs<RoomActiveSessionStore>(koin.get<ActiveSessionStore>())
        // Story 4.13: the runtime carries out every effect behind the confirm sheet's effect tap.
        assertIs<SheetEffectRunner>(koin.get<EffectRunner>(), "the wake runtime is the one effect runner, behind the sheet's tap")
        assertSame(koin.get<EffectRunner>(), koin.get<EffectRunner>())
        // Until 4.12 binds the Play adapter, no live price or billing country is known.
        assertIs<PlayLivePrices>(koin.get<LivePriceSource>(), "the live price comes from Play (Story 4.12)")
        assertIs<CatalogDisplayPrices>(koin.get<DisplayPrices>(), "display prices come from the 4.3 price cache")
        assertIs<PlayBillingCountry>(koin.get<BillingCountry>(), "the billing country comes from Play (Story 4.12)")
        assertSame(koin.get<WakeRuntime>(), koin.get<WakeRuntime>())
        // With a CI google-services.json the app is configured and binds Crashlytics; without it, the no-op reporter.
        val app = ApplicationProvider.getApplicationContext<Context>()
        if (isFirebaseConfigured(app)) {
            assertIs<FirebaseCrashReporter>(koin.get<CrashReporter>())
        } else {
            assertIs<NoOpCrashReporter>(koin.get<CrashReporter>())
        }
        assertIs<AndroidBilling>(koin.get<Billing>())
        assertSame(koin.get<SessionRecorder>(), koin.get<SessionRecorder>())
        assertIs<RoomSessionHistoryRepository>(koin.get<SessionHistoryRepository>())
        // Story 6.2: the stats snapshot for Home and Progress resolves over the history, the records and the time ports.
        assertNotNull(koin.get<ObserveProgress>())
        assertNotNull(koin.get<DayTicker>())
    }

    @Test
    fun `Koin binds the Epic 1 production policies`() {
        val koin = GlobalContext.get()

        // Story 4.7: every reason over the live env. The policy (the reducer's) reads the one SnoozeConditions single that
        // the wake screen injects and collects, so the screen and the reducer agree (review fix).
        val policy = assertIs<LiveSnoozeAvailability>(koin.get<SnoozeAvailabilityPolicy>())
        assertSame(koin.get<SnoozeConditions>(), policy.conditions)
        assertIs<AndroidUserLockState>(koin.get<UserLockState>())
        assertSame(PluginCheckValidator, koin.get<CheckValidator>())
        assertIs<CameraFallbackPolicy>(koin.get<FallbackPolicy>())
        assertSame(UsdFeeLadder, koin.get<FeeLadder>())
        assertIs<AndroidMoneyFormatter>(koin.get<MoneyFormatter>())
    }

    @Test
    fun `the production graph, without the test overrides, binds the real connectivity under the snooze policy`() {
        // testAppModule replaces Connectivity with a fake in every other test: a lost production binding would only
        // crash the wake screen on a phone (review fix). Only the app's own modules here.
        stopApp()
        val koin =
            startKoin {
                androidContext(ApplicationProvider.getApplicationContext())
                modules(appModule, dataModule, uiModule)
            }.koin

        assertIs<AndroidConnectivity>(koin.get<Connectivity>())
        val policy = assertIs<LiveSnoozeAvailability>(koin.get<SnoozeAvailabilityPolicy>())
        assertSame(koin.get<SnoozeConditions>(), policy.conditions)
        assertNotNull(koin.get<SessionStartPriceRefresh>())
    }

    @Test
    fun `host tests run on the test application, which replaces any Koin graph left running`() {
        val app = assertIs<TestYawnAndPawnApp>(ApplicationProvider.getApplicationContext())
        val before = GlobalContext.get()

        app.onCreate()

        assertNotSame(before, GlobalContext.get())
        GlobalContext.get().get<ApplicationScope>().awaitChildren()
    }

    /**
     * Story 5.3 review item 1: the production engine reads the real keyguard adapter when a test fires and stores a
     * completed locked test in the real records; a test rung unlocked stores nothing.
     */
    @Test
    fun `the app's engine stores a test completed after a locked fire as the locked-screen test's pass`() {
        assertEquals(true, lockedTestPassed(keyguardLocked = true))
    }

    @Test
    fun `the app's engine stores nothing for a test fired unlocked`() {
        assertEquals(false, lockedTestPassed(keyguardLocked = false))
    }

    /** Rings a test with the keyguard [keyguardLocked], completes it, and returns whether a locked-test pass is stored. */
    private fun lockedTestPassed(keyguardLocked: Boolean): Boolean {
        val app = WakeApp()
        val keyguard = shadowOf(app.app.getSystemService(KeyguardManager::class.java))
        try {
            keyguard.setKeyguardLocked(keyguardLocked)
            app.dispatch(SessionEvent.TestAlarmFired("test-session", aSessionConfig(testMode = true), beforeFirstUnlock = false))
            val test = assertIs<SessionState.Ringing>(app.engine.state.value)
            assertEquals(keyguardLocked, test.session.config.testStartedLocked)
            app.dispatch(SessionEvent.ImUpTapped)
            app.solveCheck()
            app.awaitUntil("the test is completed and recorded") { app.engine.state.value == SessionState.Idle }
            assertIs<SessionState.Completed>(app.engine.ended.value)
            val appWork = assertNotNull(app.koin.get<ApplicationScope>().coroutineContext[Job])
            app.awaitUntil("the app's background work is done") { appWork.children.none { it.isActive } }

            val stored = assertIs<Outcome.Success<StoredReliability>>(runBlocking { app.koin.get<ReliabilityRecords>().read() })
            return stored.value.lockedTestPassedAt != null
        } finally {
            ShadowKeyguardManager.reset()
        }
    }

    @Test
    fun `with nothing stored the app starts with the engine Idle`() {
        val koin = GlobalContext.get()
        koin.get<ApplicationScope>().awaitChildren()

        assertEquals(SessionState.Idle, koin.get<SessionEngine>().state.value)
    }
}

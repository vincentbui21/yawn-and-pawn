package com.yawnandpawn.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.android.AndroidUserLockState
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.UnavailableBilling
import com.yawnandpawn.app.android.crash.FirebaseCrashReporter
import com.yawnandpawn.app.android.crash.isFirebaseConfigured
import com.yawnandpawn.app.android.wake.CatalogDisplayPrices
import com.yawnandpawn.app.android.wake.NoOpCrashReporter
import com.yawnandpawn.app.android.wake.SheetEffectRunner
import com.yawnandpawn.app.android.wake.WakeRuntime
import com.yawnandpawn.app.core.billing.BillingCountry
import com.yawnandpawn.app.core.billing.DisplayPrices
import com.yawnandpawn.app.core.billing.FeeLadder
import com.yawnandpawn.app.core.billing.LivePriceSource
import com.yawnandpawn.app.core.billing.LiveSnoozeAvailability
import com.yawnandpawn.app.core.billing.MoneyFormatter
import com.yawnandpawn.app.core.billing.SnoozeConditions
import com.yawnandpawn.app.core.billing.UsdFeeLadder
import com.yawnandpawn.app.core.crash.CrashReporter
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.CameraFallbackPolicy
import com.yawnandpawn.app.core.session.CheckValidator
import com.yawnandpawn.app.core.session.EffectRunner
import com.yawnandpawn.app.core.session.FallbackPolicy
import com.yawnandpawn.app.core.session.PluginCheckValidator
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionRecorder
import com.yawnandpawn.app.core.session.SessionReducer
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailabilityPolicy
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.data.history.RoomSessionHistoryRepository
import com.yawnandpawn.app.data.session.RoomActiveSessionStore
import com.yawnandpawn.app.ui.format.AndroidMoneyFormatter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    fun `Koin binds one session engine, its reducer, the Room session store, the history recorder, the wake runtime and Epic 1 billing`() {
        val koin = GlobalContext.get()

        assertSame(koin.get<SessionEngine>(), koin.get<SessionEngine>())
        assertSame(koin.get<SessionReducer>(), koin.get<SessionReducer>())
        assertIs<RoomActiveSessionStore>(koin.get<ActiveSessionStore>())
        // Story 4.13: the runtime carries out every effect behind the confirm sheet's effect tap.
        assertIs<SheetEffectRunner>(koin.get<EffectRunner>(), "the wake runtime is the one effect runner, behind the sheet's tap")
        assertSame(koin.get<EffectRunner>(), koin.get<EffectRunner>())
        // Until 4.12 binds the Play adapter, no live price or billing country is known.
        assertSame(LivePriceSource.None, koin.get<LivePriceSource>())
        assertIs<CatalogDisplayPrices>(koin.get<DisplayPrices>(), "display prices come from the 4.3 price cache")
        assertSame(BillingCountry.None, koin.get<BillingCountry>())
        assertSame(koin.get<WakeRuntime>(), koin.get<WakeRuntime>())
        // With a CI google-services.json the app is configured and binds Crashlytics; without it, the no-op reporter.
        val app = ApplicationProvider.getApplicationContext<Context>()
        if (isFirebaseConfigured(app)) {
            assertIs<FirebaseCrashReporter>(koin.get<CrashReporter>())
        } else {
            assertIs<NoOpCrashReporter>(koin.get<CrashReporter>())
        }
        assertIs<UnavailableBilling>(koin.get<Billing>())
        assertSame(koin.get<SessionRecorder>(), koin.get<SessionRecorder>())
        assertIs<RoomSessionHistoryRepository>(koin.get<SessionHistoryRepository>())
    }

    @Test
    fun `Koin binds the Epic 1 production policies`() {
        val koin = GlobalContext.get()

        // Story 4.7: every reason over the live env; the conditions are one instance, shared with the wake screen.
        assertIs<LiveSnoozeAvailability>(koin.get<SnoozeAvailabilityPolicy>())
        assertSame(koin.get<SnoozeConditions>(), koin.get<SnoozeConditions>())
        assertIs<AndroidUserLockState>(koin.get<UserLockState>())
        assertSame(PluginCheckValidator, koin.get<CheckValidator>())
        assertIs<CameraFallbackPolicy>(koin.get<FallbackPolicy>())
        assertSame(UsdFeeLadder, koin.get<FeeLadder>())
        assertIs<AndroidMoneyFormatter>(koin.get<MoneyFormatter>())
    }

    @Test
    fun `host tests run on the test application, which replaces any Koin graph left running`() {
        val app = assertIs<TestYawnAndPawnApp>(ApplicationProvider.getApplicationContext())
        val before = GlobalContext.get()

        app.onCreate()

        assertNotSame(before, GlobalContext.get())
        GlobalContext.get().get<ApplicationScope>().awaitChildren()
    }

    @Test
    fun `with nothing stored the app starts with the engine Idle`() {
        val koin = GlobalContext.get()
        koin.get<ApplicationScope>().awaitChildren()

        assertEquals(SessionState.Idle, koin.get<SessionEngine>().state.value)
    }
}

package com.yawnandpawn.app

import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.UnavailableBilling
import com.yawnandpawn.app.android.wake.NoOpCrashReporter
import com.yawnandpawn.app.android.wake.WakeRuntime
import com.yawnandpawn.app.core.crash.CrashReporter
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.CheckValidator
import com.yawnandpawn.app.core.session.EffectRunner
import com.yawnandpawn.app.core.session.FallbackPolicy
import com.yawnandpawn.app.core.session.FeeLadder
import com.yawnandpawn.app.core.session.NoBillingSnoozeAvailability
import com.yawnandpawn.app.core.session.NoFallbackPolicy
import com.yawnandpawn.app.core.session.PlaceholderCheckValidator
import com.yawnandpawn.app.core.session.RandomSeedSource
import com.yawnandpawn.app.core.session.SeedSource
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionRecorder
import com.yawnandpawn.app.core.session.SessionReducer
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailabilityPolicy
import com.yawnandpawn.app.core.session.TierFeeLadder
import com.yawnandpawn.app.data.history.RoomSessionHistoryRepository
import com.yawnandpawn.app.data.session.RoomActiveSessionStore
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
        assertSame(koin.get<WakeRuntime>(), koin.get<EffectRunner>(), "the wake runtime is the one effect runner")
        assertSame(koin.get<WakeRuntime>(), koin.get<WakeRuntime>())
        assertIs<NoOpCrashReporter>(koin.get<CrashReporter>(), "no google-services.json in tests: the no-op reporter")
        assertIs<RandomSeedSource>(koin.get<SeedSource>())
        assertIs<UnavailableBilling>(koin.get<Billing>())
        assertSame(koin.get<SessionRecorder>(), koin.get<SessionRecorder>())
        assertIs<RoomSessionHistoryRepository>(koin.get<SessionHistoryRepository>())
    }

    @Test
    fun `Koin binds the Epic 1 production policies`() {
        val koin = GlobalContext.get()

        assertSame(NoBillingSnoozeAvailability, koin.get<SnoozeAvailabilityPolicy>())
        assertSame(PlaceholderCheckValidator, koin.get<CheckValidator>())
        assertSame(NoFallbackPolicy, koin.get<FallbackPolicy>())
        assertSame(TierFeeLadder, koin.get<FeeLadder>())
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

package com.yawnandpawn.app

import android.app.Application
import com.yawnandpawn.app.android.AndroidAlarmScheduler
import com.yawnandpawn.app.android.AndroidLogger
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.LoggingEffectRunner
import com.yawnandpawn.app.android.UnavailableBilling
import com.yawnandpawn.app.android.androidTimeModule
import com.yawnandpawn.app.core.alarm.AlarmFiredHandler
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.alarm.AlarmScheduling
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.DeleteAlarm
import com.yawnandpawn.app.core.alarm.DuplicateAlarm
import com.yawnandpawn.app.core.alarm.RearmOnFire
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.alarm.SetAlarmEnabled
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.id.UuidV4IdGenerator
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.CheckValidator
import com.yawnandpawn.app.core.session.EffectRunner
import com.yawnandpawn.app.core.session.FallbackPolicy
import com.yawnandpawn.app.core.session.FeeLadder
import com.yawnandpawn.app.core.session.NoBillingSnoozeAvailability
import com.yawnandpawn.app.core.session.NoFallbackPolicy
import com.yawnandpawn.app.core.session.PlaceholderCheckValidator
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionReducer
import com.yawnandpawn.app.core.session.SnoozeAvailabilityPolicy
import com.yawnandpawn.app.core.session.TierFeeLadder
import com.yawnandpawn.app.data.dataModule
import com.yawnandpawn.app.ui.uiModule
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

/** Koin bindings of :androidApp (platform adapters, core wiring). Later stories add their bindings here. */
val appModule =
    module {
        includes(androidTimeModule)
        single<IdGenerator> { UuidV4IdGenerator() }
        single<Logger> { AndroidLogger() }
        single { ApplicationScope(get()) }
        // Alarm use cases (Story 1.7); the repository and the request-code sequence come from dataModule, Clock and
        // TimeZoneProvider from androidTimeModule.
        // One lock shared by every alarm use case and by AlarmScheduling: it serializes their read-modify-write.
        single { AlarmWriteLock() }
        // Scheduling (Story 1.10): the only AlarmScheduler, the sync helper and the Epic 1 fire handler (Story 1.14
        // rebinds the handler to the wake runtime).
        single<AlarmScheduler> { AndroidAlarmScheduler(androidContext(), get(), get(), get(), get()) }
        single { AlarmScheduling(get(), get(), get(), get(), get(), get()) }
        single<AlarmFiredHandler> { RearmOnFire(get(), get(), get(), get(), get(), get()) }
        factory { SaveAlarm(get(), get(), get(), get(), get(), get()) }
        factory { SetAlarmEnabled(get(), get(), get(), get()) }
        factory { DeleteAlarm(get(), get(), get()) }
        factory { DuplicateAlarm(get(), get(), get(), get(), get(), get()) }
        // The wake session (Story 1.12): the Epic 1 policies, the one engine over runtime.db (ActiveSessionStore from
        // dataModule) and the real time ports. Effects are only logged until the wake runtime (Story 1.14); billing
        // stays unavailable until Epic 4.
        single<SnoozeAvailabilityPolicy> { NoBillingSnoozeAvailability }
        single<CheckValidator> { PlaceholderCheckValidator }
        single<FallbackPolicy> { NoFallbackPolicy }
        single<FeeLadder> { TierFeeLadder }
        single { SessionReducer(get(), get(), get()) }
        single<EffectRunner> { LoggingEffectRunner(get()) }
        single<Billing> { UnavailableBilling(get()) }
        single { SessionEngine(get(), get(), get(), get(), get(), get(), get()) }
    }

/** The app process: starts Koin, re-arms alarms and restores the session. Open for the Robolectric test application. */
open class YawnAndPawnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val koin =
            startKoin {
                androidContext(this@YawnAndPawnApp)
                modules(appModule, dataModule, uiModule)
            }.koin
        val scope = koin.get<ApplicationScope>()
        // App start re-arms every alarm (AD-4); it also covers a backup restore, which restarts the app.
        val scheduling = koin.get<AlarmScheduling>()
        scope.launch { scheduling.rescheduleAll() }
        // Then the engine takes over a session the last process left in runtime.db (AD-2 rule 2): entry effects only.
        val engine = koin.get<SessionEngine>()
        scope.launch { engine.restore() }
    }
}

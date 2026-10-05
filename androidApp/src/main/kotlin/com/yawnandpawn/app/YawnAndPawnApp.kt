package com.yawnandpawn.app

import android.app.Application
import com.yawnandpawn.app.android.AndroidAlarmScheduler
import com.yawnandpawn.app.android.AndroidLogger
import com.yawnandpawn.app.android.AndroidUserLockState
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.UnavailableBilling
import com.yawnandpawn.app.android.androidTimeModule
import com.yawnandpawn.app.android.crash.FirebaseStartup
import com.yawnandpawn.app.android.reliability.reliabilityModule
import com.yawnandpawn.app.android.sound.soundModule
import com.yawnandpawn.app.android.wake.WakeAlarmFiredHandler
import com.yawnandpawn.app.android.wake.WakeRuntime
import com.yawnandpawn.app.android.wake.wakeModule
import com.yawnandpawn.app.core.alarm.AlarmFiredHandler
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.alarm.AlarmScheduling
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.DeleteAlarm
import com.yawnandpawn.app.core.alarm.RearmOnFire
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.alarm.SetAlarmEnabled
import com.yawnandpawn.app.core.error.valueOrNull
import com.yawnandpawn.app.core.history.MissedNotes
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.id.UuidV4IdGenerator
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.CheckValidator
import com.yawnandpawn.app.core.session.EffectRunner
import com.yawnandpawn.app.core.session.FallbackPolicy
import com.yawnandpawn.app.core.session.FeeLadder
import com.yawnandpawn.app.core.session.NoBillingSnoozeAvailability
import com.yawnandpawn.app.core.session.NoFallbackPolicy
import com.yawnandpawn.app.core.session.PlaceholderCheckValidator
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionRecorder
import com.yawnandpawn.app.core.session.SessionReducer
import com.yawnandpawn.app.core.session.SessionSlotRearm
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailabilityPolicy
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.core.session.TierFeeLadder
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.data.dataModule
import com.yawnandpawn.app.ui.uiModule
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

/** Koin bindings of :androidApp (platform adapters, core wiring). Later stories add their bindings here. */
val appModule =
    module {
        includes(androidTimeModule, wakeModule(), soundModule(), reliabilityModule())
        single<IdGenerator> { UuidV4IdGenerator() }
        single<Logger> { AndroidLogger() }
        single { ApplicationScope(get()) }
        // Alarm use cases (Story 1.7); the repository and the request-code sequence come from dataModule, Clock and
        // TimeZoneProvider from androidTimeModule.
        // One lock shared by every alarm use case and by AlarmScheduling: it serializes their read-modify-write.
        single { AlarmWriteLock() }
        // Scheduling (Story 1.10): the only AlarmScheduler and the sync helper. A fire rings through the wake service
        // (Story 1.14), then re-arms through RearmOnFire (device test round 1: the ring first).
        single<AlarmScheduler> { AndroidAlarmScheduler(androidContext(), get(), get(), get(), get()) }
        single { AlarmScheduling(get(), get(), get(), get(), get(), get()) }
        single { RearmOnFire(get(), get(), get(), get(), get(), get()) }
        single<AlarmFiredHandler> {
            WakeAlarmFiredHandler(get(), get<RearmOnFire>(), get(), get(), starts = get(), timings = get(), rearm = get())
        }
        // The session slot armed from runtime.db without the engine (Story 2.1): after system events and refused starts.
        single { SessionSlotRearm(get(), get(), get(), get(), get(), get()) }
        factory { SaveAlarm(get(), get(), get(), get(), get(), get()) }
        factory { SetAlarmEnabled(get(), get(), get(), get()) }
        factory { DeleteAlarm(get(), get(), get()) }
        // "Test alarm" (Story 1.18): the editor's values ring as a test 10 s later, through the test request code.
        factory { ScheduleTestAlarm(get(), get(), get(), get()) }
        // The wake session (Story 1.12): the Epic 1 policies, the one engine over runtime.db (ActiveSessionStore from
        // dataModule) and the real time ports. The wake runtime (Story 1.14) carries out its effects; billing stays
        // unavailable until Epic 4.
        // Before the first unlock (Story 2.3): the engine marks the ring, and snooze says "Unlock your phone to snooze".
        single<UserLockState> { AndroidUserLockState(androidContext()) }
        single<SnoozeAvailabilityPolicy> { NoBillingSnoozeAvailability(get()) }
        single<CheckValidator> { PlaceholderCheckValidator }
        single<FallbackPolicy> { NoFallbackPolicy }
        single<FeeLadder> { TierFeeLadder }
        single { SessionReducer(get(), get(), get()) }
        single<EffectRunner> { get<WakeRuntime>() }
        single<Billing> { UnavailableBilling(get()) }
        // The only writer of session history (Story 1.13, AD-18), over the Room repository from dataModule; the engine
        // drives it itself, so the runner never sees the history effects.
        single { SessionRecorder(get()) }
        // Home's missed note (Story 1.16): the latest Missed history row, unless dismissed (settings DataStore).
        single { MissedNotes(get(), get()) }
        single { SessionEngine(get(), get(), get(), get(), get(), get(), get(), get(), userLock = get()) }
    }

/**
 * The app process: starts Koin and re-arms alarms. It never restores the session (Story 2.1): a process started for a
 * broadcast must not run the ringing entry effects or start a foreground service. `WakeService`, `MainActivity` and
 * `WakeActivity` restore it; a system event arms the session slot instead. Open for the Robolectric test application.
 */
open class YawnAndPawnApp : Application() {
    /** Bindings loaded after the app's own (they win); only the Robolectric test application adds any. */
    protected open val overrideModules: List<Module> = emptyList()

    override fun onCreate() {
        super.onCreate()
        val koin =
            startKoin {
                androidContext(this@YawnAndPawnApp)
                modules(listOf(appModule, dataModule, uiModule) + overrideModules)
            }.koin
        // Crashlytics (Story 1.19): only with a Firebase configuration, and only once the user has unlocked (AD-15).
        koin.get<FirebaseStartup>().start()
        val scope = koin.get<ApplicationScope>()
        // App start re-arms every alarm (AD-4); it also covers a backup restore, which restarts the app.
        val scheduling = koin.get<AlarmScheduling>()
        scope.launch { scheduling.rescheduleAll() }
        // With no session left in runtime.db (nothing, an unreadable row or a stored Idle), an alarm volume a crashed session
        // saved is put back (AD-5). Only a read: the session itself is restored by WakeService, MainActivity or
        // WakeActivity (Story 2.1).
        val store = koin.get<ActiveSessionStore>()
        val runtime = koin.get<WakeRuntime>()
        scope.launch {
            if (store.load().valueOrNull()?.holdsNoSession() == true) runtime.restoreVolumeIfIdle()
        }
    }

    private fun StoredSession.holdsNoSession(): Boolean =
        this == StoredSession.Empty || this is StoredSession.Unreadable || this == StoredSession.Found(SessionState.Idle)
}

package com.yawnandpawn.app

import android.app.Application
import com.yawnandpawn.app.android.AndroidAccessibilityState
import com.yawnandpawn.app.android.AndroidAlarmScheduler
import com.yawnandpawn.app.android.AndroidLogger
import com.yawnandpawn.app.android.AndroidUserLockState
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.UnavailableBilling
import com.yawnandpawn.app.android.WordListLoader
import com.yawnandpawn.app.android.androidTimeModule
import com.yawnandpawn.app.android.crash.FirebaseStartup
import com.yawnandpawn.app.android.qr.qrModule
import com.yawnandpawn.app.android.reliability.reliabilityModule
import com.yawnandpawn.app.android.screen.AndroidWakeScreenOpener
import com.yawnandpawn.app.android.sound.soundModule
import com.yawnandpawn.app.android.wake.WakeAlarmFiredHandler
import com.yawnandpawn.app.android.wake.WakeRuntime
import com.yawnandpawn.app.android.wake.wakeModule
import com.yawnandpawn.app.core.alarm.AlarmFiredHandler
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.alarm.AlarmScheduling
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.DeleteAlarm
import com.yawnandpawn.app.core.alarm.ReRegisterCode
import com.yawnandpawn.app.core.alarm.RearmOnFire
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.alarm.SetAlarmEnabled
import com.yawnandpawn.app.core.billing.FeeLadder
import com.yawnandpawn.app.core.billing.MoneyFormatter
import com.yawnandpawn.app.core.billing.PurgeOldPurchaseIntents
import com.yawnandpawn.app.core.billing.UsdFeeLadder
import com.yawnandpawn.app.core.checks.AccessibilityState
import com.yawnandpawn.app.core.checks.word.WordBank
import com.yawnandpawn.app.core.config.PromotePendingChanges
import com.yawnandpawn.app.core.config.ReadFireSettings
import com.yawnandpawn.app.core.config.RecordCommitmentEvent
import com.yawnandpawn.app.core.config.SaveGlobalSetting
import com.yawnandpawn.app.core.config.SetBaseFee
import com.yawnandpawn.app.core.config.SetMaxSnoozes
import com.yawnandpawn.app.core.error.valueOrNull
import com.yawnandpawn.app.core.history.MissedNotes
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.id.UuidV4IdGenerator
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.CameraFallbackPolicy
import com.yawnandpawn.app.core.session.CheckValidator
import com.yawnandpawn.app.core.session.EffectRunner
import com.yawnandpawn.app.core.session.FallbackPolicy
import com.yawnandpawn.app.core.session.NoBillingSnoozeAvailability
import com.yawnandpawn.app.core.session.PluginCheckValidator
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionRecorder
import com.yawnandpawn.app.core.session.SessionReducer
import com.yawnandpawn.app.core.session.SessionSlotRearm
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailabilityPolicy
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.stats.ReRegisterSuggestions
import com.yawnandpawn.app.data.dataModule
import com.yawnandpawn.app.ui.format.moneyFormatter
import com.yawnandpawn.app.ui.nav.WakeScreenOpener
import com.yawnandpawn.app.ui.uiModule
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

/** Koin bindings of :androidApp (platform adapters, core wiring). Later stories add their bindings here. */
val appModule =
    module {
        includes(androidTimeModule, wakeModule(), soundModule(), reliabilityModule(), qrModule())
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
        // rescheduleAll() promotes the due pending changes first (Story 4.4).
        single { AlarmScheduling(get(), get(), get(), get(), get(), get(), promotion = get<PromotePendingChanges>()) }
        // The commitment lock (Story 4.4): promotion skips the occurrence the session in progress rings, read lazily (the
        // engine is built after the scheduling it may need).
        // Before the engine is restored (a cold start), the stored session in runtime.db is read instead (review fix 9).
        single {
            val scope = this
            PromotePendingChanges(get(), get(), get(), get(), get(), get(), get()) {
                val engine = scope.get<SessionEngine>()
                PromotePendingChanges.occurrenceOf(engine.state.value) ?: if (engine.restored.value) {
                    null
                } else {
                    (scope.get<ActiveSessionStore>().load().valueOrNull() as? StoredSession.Found)
                        ?.let { PromotePendingChanges.occurrenceOf(it.state) }
                }
            }
        }
        // The fire's settings read (review fix 11): one snapshot within 500 ms, the last-known settings otherwise.
        factory { ReadFireSettings(get(), get(), get()) }
        factory { SaveGlobalSetting(get(), get(), get(), get(), get(), get(), get()) }
        factory { SetBaseFee(get()) }
        factory { SetMaxSnoozes(get()) }
        factory { RecordCommitmentEvent(get(), get(), get(), get(), get()) }
        single { RearmOnFire(get(), get(), get(), get(), get()) }
        single<AlarmFiredHandler> {
            WakeAlarmFiredHandler(get(), get<RearmOnFire>(), get(), get(), starts = get(), timings = get(), rearm = get())
        }
        // The session slot armed from runtime.db without the engine (Story 2.1): after system events and refused starts.
        single { SessionSlotRearm(get(), get(), get(), get(), get(), get()) }
        factory { SaveAlarm(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        factory { SetAlarmEnabled(get(), get(), get(), get(), get()) }
        // Story 3.10: Home's "Re-register" stores a new code alone.
        factory { ReRegisterCode(get(), get(), get(), get(), get()) }
        factory { DeleteAlarm(get(), get(), get(), get(), get()) }
        // "Test alarm" (Story 1.18): the editor's values ring as a test 10 s later, through the test request code.
        factory { ScheduleTestAlarm(get(), get(), get(), get()) }
        // The wake session (Story 1.12): the Epic 1 policies, the one engine over runtime.db (ActiveSessionStore from
        // dataModule) and the real time ports. The wake runtime (Story 1.14) carries out its effects; billing stays
        // unavailable until Epic 4.
        // Before the first unlock (Story 2.3): the engine marks the ring, and snooze says "Unlock your phone to snooze".
        single<UserLockState> { AndroidUserLockState(androidContext(), get()) }
        // TalkBack (Story 3.8): the numbered Memory Sequence when a ring's plan is frozen, and the editor's notes.
        single<AccessibilityState> { AndroidAccessibilityState(androidContext()) }
        single<SnoozeAvailabilityPolicy> { NoBillingSnoozeAvailability(get()) }
        single<CheckValidator> { PluginCheckValidator }
        single<FallbackPolicy> { CameraFallbackPolicy() }
        // The fee ladder (Story 4.2): snooze N at base fee B is product snooze_usd_NN with NN = B x N, capped at 50.
        single<FeeLadder> { UsdFeeLadder }
        // Money as text (AD-8): the phone's locale and the currency's own digits; the UI uses the same formatter.
        single<MoneyFormatter> { moneyFormatter }
        single { SessionReducer(get(), get(), get()) }
        single<EffectRunner> { get<WakeRuntime>() }
        single<Billing> { UnavailableBilling(get()) }
        // Story 4.8: intents older than 7 days are deleted on app start (runtime.db, device-protected).
        factory { PurgeOldPurchaseIntents(get(), get(), get()) }
        // The only writer of session history (Story 1.13, AD-18), over the Room repository from dataModule; the engine
        // drives it itself, so the runner never sees the history effects.
        single { SessionRecorder(get()) }
        // Home's missed note (Story 1.16): the latest Missed history row, unless dismissed (settings DataStore).
        single { MissedNotes(get(), get()) }
        // Home's re-register banner (Story 3.13): 3 fallbacks for a camera check in 7 days, unless dismissed since.
        single { ReRegisterSuggestions(get(), get(), get(), get()) }
        single { SessionEngine(get(), get(), get(), get(), get(), get(), get(), get(), userLock = get()) }
        // The session lock (Story 2.6): until the stored session is restored, and while a ring, a snooze or the emergency
        // ring is in progress, the alarm use cases refuse to write and the app shows only "Alarm in progress", whose
        // "Back to alarm" opens the wake screen.
        single {
            val engine = get<SessionEngine>()
            SessionLockGuard(engine.state, engine.restored, get<WakeRuntime>().emergencyRinging)
        }
        single<WakeScreenOpener> {
            AndroidWakeScreenOpener(
                androidContext(),
                get<SessionEngine>().state,
                get<WakeRuntime>().emergencyRinging,
            )
        }
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
        // The Word Unscramble list (Story 3.7), before any ring, restore or preview can make a Word puzzle.
        WordBank.install(WordListLoader.load(this, AndroidLogger()))
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
        // It also promotes the due pending changes of the commitment lock first (Story 4.4); the wake service promotes
        // them again when a session is over.
        scope.launch { scheduling.rescheduleAll() }
        // Purchase intents are kept 7 days, long enough to price a pending payment that completes later (Story 4.8).
        val purgeIntents = koin.get<PurgeOldPurchaseIntents>()
        scope.launch { purgeIntents() }
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

package com.yawnandpawn.app

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.yawnandpawn.app.android.AndroidAccessibilityState
import com.yawnandpawn.app.android.AndroidAlarmScheduler
import com.yawnandpawn.app.android.AndroidConnectivity
import com.yawnandpawn.app.android.AndroidLogger
import com.yawnandpawn.app.android.AndroidUserLockState
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.WordListLoader
import com.yawnandpawn.app.android.androidTimeModule
import com.yawnandpawn.app.android.billing.ResumedWakeScreen
import com.yawnandpawn.app.android.billing.billingModule
import com.yawnandpawn.app.android.crash.CrashlyticsSink
import com.yawnandpawn.app.android.crash.CrashlyticsUnsentReports
import com.yawnandpawn.app.android.crash.FirebaseStartup
import com.yawnandpawn.app.android.crash.isFirebaseConfigured
import com.yawnandpawn.app.android.qr.qrModule
import com.yawnandpawn.app.android.recording.StartedActivities
import com.yawnandpawn.app.android.recording.recordingModule
import com.yawnandpawn.app.android.reliability.reliabilityModule
import com.yawnandpawn.app.android.screen.AndroidExternalLinks
import com.yawnandpawn.app.android.screen.AndroidWakeScreenOpener
import com.yawnandpawn.app.android.sound.soundModule
import com.yawnandpawn.app.android.telemetry.AnalyticsConsentGate
import com.yawnandpawn.app.android.telemetry.telemetryModule
import com.yawnandpawn.app.android.wake.SheetEffectRunner
import com.yawnandpawn.app.android.wake.WakeAlarmFiredHandler
import com.yawnandpawn.app.android.wake.WakeRuntime
import com.yawnandpawn.app.android.wake.wakeModule
import com.yawnandpawn.app.android.work.workModule
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
import com.yawnandpawn.app.core.billing.LiveSnoozeAvailability
import com.yawnandpawn.app.core.billing.MoneyFormatter
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.billing.PriceRefreshScheduler
import com.yawnandpawn.app.core.billing.PriceSnapshotLookup
import com.yawnandpawn.app.core.billing.PurchaseCoordinator
import com.yawnandpawn.app.core.billing.PurchaseLedger
import com.yawnandpawn.app.core.billing.PurgeOldPurchaseIntents
import com.yawnandpawn.app.core.billing.ReplayGrantLedger
import com.yawnandpawn.app.core.billing.SnoozeConditions
import com.yawnandpawn.app.core.billing.UsdFeeLadder
import com.yawnandpawn.app.core.checks.AccessibilityState
import com.yawnandpawn.app.core.checks.word.WordBank
import com.yawnandpawn.app.core.config.CommitmentLog
import com.yawnandpawn.app.core.config.PromotePendingChanges
import com.yawnandpawn.app.core.config.ReadFireSettings
import com.yawnandpawn.app.core.config.RecordCommitmentEvent
import com.yawnandpawn.app.core.config.SaveDefaultSetting
import com.yawnandpawn.app.core.config.SaveGlobalSetting
import com.yawnandpawn.app.core.config.SetBaseFee
import com.yawnandpawn.app.core.config.SetBrightWakeScreen
import com.yawnandpawn.app.core.config.SetDefaultGrace
import com.yawnandpawn.app.core.config.SetDefaultSnoozeLength
import com.yawnandpawn.app.core.config.SetDefaultVibrateInGrace
import com.yawnandpawn.app.core.config.SetMaxSnoozes
import com.yawnandpawn.app.core.config.SetThemeMode
import com.yawnandpawn.app.core.crash.UnsentCrashReports
import com.yawnandpawn.app.core.error.valueOrNull
import com.yawnandpawn.app.core.history.MissedNotes
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.id.UuidV4IdGenerator
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.net.Connectivity
import com.yawnandpawn.app.core.onboarding.AcceptDisclosure
import com.yawnandpawn.app.core.onboarding.CompleteOnboarding
import com.yawnandpawn.app.core.onboarding.OnboardingTestNote
import com.yawnandpawn.app.core.onboarding.RememberOnboardingAlarm
import com.yawnandpawn.app.core.onboarding.RememberOnboardingTest
import com.yawnandpawn.app.core.onboarding.SaveOnboardingStep
import com.yawnandpawn.app.core.reliability.LockedTestReporter
import com.yawnandpawn.app.core.reliability.ReliabilityRecords
import com.yawnandpawn.app.core.reset.DeleteAllData
import com.yawnandpawn.app.core.reset.DeletionMarker
import com.yawnandpawn.app.core.reset.PendingDeletion
import com.yawnandpawn.app.core.reset.ResumePendingDeletion
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.CameraFallbackPolicy
import com.yawnandpawn.app.core.session.CheckValidator
import com.yawnandpawn.app.core.session.EffectRunner
import com.yawnandpawn.app.core.session.FallbackPolicy
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
import com.yawnandpawn.app.core.stats.DayTicker
import com.yawnandpawn.app.core.stats.ObserveProgress
import com.yawnandpawn.app.core.stats.ReRegisterSuggestions
import com.yawnandpawn.app.core.summary.WeeklySummaryScheduler
import com.yawnandpawn.app.core.telemetry.Telemetry
import com.yawnandpawn.app.core.telemetry.TelemetryReporter
import com.yawnandpawn.app.data.dataModule
import com.yawnandpawn.app.ui.format.moneyFormatter
import com.yawnandpawn.app.ui.links.AppLinks
import com.yawnandpawn.app.ui.links.ExternalLinks
import com.yawnandpawn.app.ui.nav.WakeScreenOpener
import com.yawnandpawn.app.ui.settings.SettingsViewModel
import com.yawnandpawn.app.ui.uiModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Koin bindings of :androidApp (platform adapters, core wiring). Later stories add their bindings here. */
val appModule =
    module {
        includes(
            androidTimeModule,
            wakeModule(),
            soundModule(),
            reliabilityModule(),
            qrModule(),
            workModule(),
            billingModule(),
            telemetryModule(),
            recordingModule(),
        )
        single<IdGenerator> { UuidV4IdGenerator() }
        single<Logger> { AndroidLogger() }
        single { ApplicationScope(get()) }
        // Story 5.1 review: a default or theme change left when Settings closes is still saved.
        single<CoroutineScope>(named(SettingsViewModel.SAVE_SCOPE)) { get<ApplicationScope>() }
        // Alarm use cases (Story 1.7); the repository and the request-code sequence come from dataModule, Clock and
        // TimeZoneProvider from androidTimeModule.
        // One lock shared by every alarm use case and by AlarmScheduling: it serializes their read-modify-write.
        single { AlarmWriteLock() }
        // Scheduling (Story 1.10): the only AlarmScheduler and the sync helper. A fire rings through the wake service
        // (Story 1.14), then re-arms through RearmOnFire (device test round 1: the ring first).
        single<AlarmScheduler> { AndroidAlarmScheduler(androidContext(), get(), get(), get(), get()) }
        // rescheduleAll() promotes the due pending changes first (Story 4.4).
        // While a "Delete all data" is pending it arms nothing (Story 5.9 review), so no start re-arms its alarms.
        single {
            AlarmScheduling(
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                promotion = get<PromotePendingChanges>(),
                deletionPending = get<PendingDeletion>().pending,
            )
        }
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
        // Story 5.1: the new-alarm defaults and the theme, no commitment lock.
        factory { SaveDefaultSetting(get(), get()) }
        factory { SetDefaultGrace(get()) }
        factory { SetDefaultVibrateInGrace(get()) }
        factory { SetDefaultSnoozeLength(get()) }
        factory { SetThemeMode(get()) }
        factory { SetBrightWakeScreen(get()) }
        factory { RecordCommitmentEvent(get(), get(), get(), get(), get()) }
        // Story 4.6: turning an alarm off or deleting it inside its lock window is logged by the alarm use cases.
        factory { CommitmentLog(get(), get(), get()) }
        single { RearmOnFire(get(), get(), get(), get(), get()) }
        single<AlarmFiredHandler> {
            WakeAlarmFiredHandler(get(), get<RearmOnFire>(), get(), get(), starts = get(), timings = get(), rearm = get())
        }
        // The session slot armed from runtime.db without the engine (Story 2.1): after system events and refused starts.
        single { SessionSlotRearm(get(), get(), get(), get(), get(), get()) }
        // Story 5.10: no alarm is saved before the disclosure is accepted (OnboardingRepository from dataModule).
        factory { SaveAlarm(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        // Onboarding (Story 5.10): the consent, the step and completion, each inside the session lock.
        factory { AcceptDisclosure(get(), get(), get()) }
        factory { SaveOnboardingStep(get(), get()) }
        factory { CompleteOnboarding(get(), get()) }
        // Story 5.11: the first alarm onboarding saved, so going back edits it instead of adding a second one.
        factory { RememberOnboardingAlarm(get(), get()) }
        // Story 5.12: when step 8's test rings, and Home's note after "Skip for now".
        factory { RememberOnboardingTest(get(), get()) }
        factory { OnboardingTestNote(get(), get(), get()) }
        factory { SetAlarmEnabled(get(), get(), get(), get(), get(), get(), get()) }
        // Story 3.10: Home's "Re-register" stores a new code alone.
        factory { ReRegisterCode(get(), get(), get(), get(), get()) }
        factory { DeleteAlarm(get(), get(), get(), get(), get(), get(), get(), get()) }
        // "Test alarm" (Story 1.18): the editor's values ring as a test 10 s later, through the test request code.
        factory { ScheduleTestAlarm(get(), get(), get(), get()) }
        // The wake session (Story 1.12): the Epic 1 policies, the one engine over runtime.db (ActiveSessionStore from
        // dataModule) and the real time ports. The wake runtime (Story 1.14) carries out its effects; Play Billing comes from
        // billingModule (Story 4.12).
        // Before the first unlock (Story 2.3): the engine marks the ring, and snooze says "Unlock your phone to snooze".
        single<UserLockState> { AndroidUserLockState(androidContext(), get()) }
        // TalkBack (Story 3.8): the numbered Memory Sequence when a ring's plan is frozen, and the editor's notes.
        single<AccessibilityState> { AndroidAccessibilityState(androidContext()) }
        // Snooze availability (Story 4.7): every reason, from the session and the live env (connectivity, cached prices,
        // the lock state; stranded payments arrive with Story 4.11). Billing stays unavailable until Story 4.12: nothing
        // fills the price cache, so snooze reads "Prices not loaded yet".
        single<Connectivity> { AndroidConnectivity(androidContext(), get()) }
        // The stranded products come from the purchase coordinator's latest full query (Story 4.11), so "An earlier
        // {price} payment is being refunded" shows while the declined product is still stranded and goes away once Play
        // no longer lists it. Its amount is what was actually paid (the purchase records, Story 4.10); unknown, the label
        // names no amount. Both are looked up when the wake screen collects: the coordinator needs the engine, whose
        // reducer needs this policy, so resolving them here would be a cycle.
        single {
            val scope = this
            SnoozeConditions(
                get(),
                get(),
                get(),
                get(),
                stranded = flow { emitAll(scope.get<PurchaseCoordinator>().strandedProducts) },
                refundingPrice = { scope.get<PurchaseLedger>().refundingPrice(it) },
            )
        }
        single<SnoozeAvailabilityPolicy> { LiveSnoozeAvailability(get(), get(), get()) }
        single<CheckValidator> { PluginCheckValidator }
        single<FallbackPolicy> { CameraFallbackPolicy() }
        // The fee ladder (Story 4.2): snooze N at base fee B is product snooze_usd_NN with NN = B x N, capped at 50.
        single<FeeLadder> { UsdFeeLadder }
        // Money as text (AD-8): the phone's locale and the currency's own digits; the UI uses the same formatter.
        single<MoneyFormatter> { moneyFormatter }
        single { SessionReducer(get(), get(), get()) }
        // Story 4.13: the confirm sheet also sees the engine's sheet effects; the runtime carries out every effect.
        single<EffectRunner> { SheetEffectRunner(get<WakeRuntime>(), get(), get()) { get<SessionEngine>().state.value } }
        // Story 4.8: intents older than 7 days are deleted on app start (runtime.db, device-protected).
        factory { PurgeOldPurchaseIntents(get(), get(), get()) }
        // Story 4.10: the only writer of purchase records and caller of consume. Its retry jobs run on WorkManager
        // (workModule, Story 4.3); a record with no intent is priced from the cached Play prices (the price snapshot),
        // else by the product's USD tier.
        single {
            val catalog = get<PriceCatalog>()
            val snapshot =
                PriceSnapshotLookup { productId ->
                    catalog
                        .observe()
                        .first()
                        .priceFor(productId)
                        ?.price
                }
            PurchaseLedger(get(), get(), get(), get(), get(), snapshot, get(), get())
        }
        factory { ReplayGrantLedger(get(), get()) }
        // The only writer of session history (Story 1.13, AD-18), over the Room repository from dataModule; the engine
        // drives it itself, so the runner never sees the history effects.
        single { SessionRecorder(get()) }
        // Home's missed note (Story 1.16): the latest Missed history row, unless dismissed (settings DataStore).
        single { MissedNotes(get(), get()) }
        // Home's re-register banner (Story 3.13): 3 fallbacks for a camera check in 7 days, unless dismissed since.
        single { ReRegisterSuggestions(get(), get(), get(), get()) }
        // Progress and Home (Epic 6, Story 6.2): the stats snapshot over session history and the purchase records, recomputed
        // on either table and when the local date or zone changes. A read only; nothing derived is stored (AD-18).
        factory { DayTicker(get(), get(), get()) }
        factory { ObserveProgress(get(), get(), get()) }
        // Billing (Story 4.12, billingModule): Play Billing 9.1 is the one Billing, and AndroidDeviceUnlocker the one UnlockPort
        // (Spike S1: the PIN before Play opens), shared by the engine (it reads the keyguard for a Pay) and the coordinator.
        // Story 5.3: a test that rang locked and is completed is stored for the checklist's locked-screen test. The write
        // is launched on ApplicationScope, never inside the engine's lock (review fix 11).
        // Story 5.6: a session's anonymous usage stats go to the consent-gated telemetry (telemetryModule) once it is recorded.
        // The consent read and the Analytics calls are launched on ApplicationScope, never inside the engine's lock (review
        // fix 1).
        single {
            SessionEngine(
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                userLock = get(),
                unlock = get(),
                lockedTest = LockedTestReporter.Launching(get<ReliabilityRecords>(), get<ApplicationScope>(), get()),
                telemetry = TelemetryReporter.Launching(telemetry = get<Telemetry>(), scope = get<ApplicationScope>(), logger = get()),
            )
        }
        // Story 4.11: the billing orchestration (launch, purchase updates, recovery, ITEM_ALREADY_OWNED, stranded reuse). It
        // runs on its own app-wide scope, never inside the engine's step.
        single {
            PurchaseCoordinator(
                billing = get(),
                engine = get(),
                ledger = get(),
                intents = get(),
                installIds = get(),
                feeLadder = get(),
                userLock = get(),
                unlock = get(),
                clock = get(),
                // Its own scope (same rules as ApplicationScope): it follows Play's updates for the life of the process and a
                // recovery may wait for a session restore that a broadcast-only process never runs, so its work is not
                // among the app start's finite jobs.
                scope = ApplicationScope(get()),
                logger = get(),
            )
        }
        // The session lock (Story 2.6): until the stored session is restored, and while a ring, a snooze or the emergency
        // ring is in progress, the alarm use cases refuse to write and the app shows only "Alarm in progress", whose
        // "Back to alarm" opens the wake screen.
        single {
            val engine = get<SessionEngine>()
            SessionLockGuard(
                engine.state,
                engine.restored,
                get<WakeRuntime>().emergencyRinging,
                deletionPending = get<PendingDeletion>().pending,
            )
        }
        single<WakeScreenOpener> {
            AndroidWakeScreenOpener(
                androidContext(),
                get<SessionEngine>().state,
                get<WakeRuntime>().emergencyRinging,
            )
        }
        // "Problem with a charge?" (Story 4.17): the order history link and the support email; the privacy policy, terms
        // and support pages (Story 5.7). All from config/app-links.properties through BuildConfig.
        single {
            AppLinks(
                supportEmail = BuildConfig.SUPPORT_EMAIL,
                orderHistoryUrl = BuildConfig.PLAY_ORDER_HISTORY_URL,
                privacyUrl = BuildConfig.PRIVACY_URL,
                termsUrl = BuildConfig.TERMS_URL,
                supportUrl = BuildConfig.SUPPORT_URL,
            )
        }
        single<ExternalLinks> { AndroidExternalLinks(androidContext(), get()) }
        // "Delete all data" (Story 5.9): an unfinished deletion holds the app (the guard refuses other writes,
        // rescheduleAll arms nothing, the restoring screen shows) until it completes or the resume gives up. One per
        // process, made from the marker (dataModule).
        single { PendingDeletion(get<DeletionMarker>().exists()) }
        // One instance; the marker, the erasers and the high-water mark come from dataModule. It runs off the main thread.
        single {
            DeleteAllData(
                lock = get(),
                sessionLock = get(),
                marker = get(),
                alarms = get(),
                highWaterMark = get(),
                scheduler = get(),
                eraser = get(),
                history = get(),
                ledger = get(),
                activeSession = get(),
                installId = get(),
                crashReports = get(),
                analytics = get(),
                work = get(),
                clock = get(),
                logger = get(),
                pendingDeletion = get(),
            )
        }
        // The deletion drops the unsent Crashlytics reports; without a Firebase configuration there are none.
        single<UnsentCrashReports> {
            val context = androidContext()
            if (isFirebaseConfigured(context)) CrashlyticsUnsentReports(CrashlyticsSink(context), get()) else UnsentCrashReports {}
        }
        // An unfinished deletion is finished from the app start, also with no activity (boot, a time change): with no
        // session stored it restores the (empty) session itself, so the guard opens; with one stored it waits for the
        // restore entry points (Story 2.1). On the app's scope, so a test's teardown cancels a resume left waiting.
        single {
            val engine = get<SessionEngine>()
            val store = get<ActiveSessionStore>()
            ResumePendingDeletion(
                deleteAllData = get(),
                pendingDeletion = get(),
                marker = get(),
                sessionLock = get(),
                scope = get<ApplicationScope>(),
                logger = get(),
                restoreSession = { if (store.load().valueOrNull()?.holdsNoSession() == true) engine.restore() },
            )
        }
    }

/**
 * The app process: starts Koin and re-arms alarms. It never restores the session (Story 2.1): a process started for a
 * broadcast must not run the ringing entry effects or start a foreground service. `WakeService`, `MainActivity` and
 * `WakeActivity` restore it; a system event arms the session slot instead. The one exception is an unfinished "Delete
 * all data" (Story 5.9 review) with no session stored: then the resume restores the empty session itself, which runs no
 * entry effect. Open for the Robolectric test application.
 *
 * It provides WorkManager's configuration (Story 4.3): the default initializer is removed from the manifest, so
 * WorkManager starts on demand at its first use, which happens only after the first unlock (`AndroidBackgroundWork`).
 */
open class YawnAndPawnApp :
    Application(),
    Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(Log.INFO).build()

    /** The resumed wake screen (Story 4.12), followed for the life of the process; billingModule binds this instance. */
    val resumedWakeScreen: ResumedWakeScreen = ResumedWakeScreen()

    /** The started-activity count (Story 7.1): a take being recorded stops when the app goes to the background. */
    val startedActivities: StartedActivities = StartedActivities()

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
        // Story 5.6: Analytics collection follows the usage stats consent once Firebase has started (never before unlock).
        koin.get<AnalyticsConsentGate>().start()
        // Story 4.12: Play's sheet and the PIN prompt open only from the resumed wake screen, followed here (once per process,
        // so a Koin graph a test restarts still sees it).
        registerActivityLifecycleCallbacks(resumedWakeScreen)
        registerActivityLifecycleCallbacks(startedActivities)
        val scope = koin.get<ApplicationScope>()
        // Story 5.9 review: an unfinished "Delete all data" holds the app and resumes, before anything re-arms alarms (a
        // start with no activity finishes it too); after 3 starts that found it, it gives up and the app runs as usual.
        koin.get<ResumePendingDeletion>().onAppStart()
        // App start re-arms every alarm (AD-4); it also covers a backup restore, which restarts the app.
        val scheduling = koin.get<AlarmScheduling>()
        // It also promotes the due pending changes of the commitment lock first (Story 4.4); the wake service promotes
        // them again when a session is over.
        scope.launch { scheduling.rescheduleAll() }
        // Purchase intents are kept 7 days, long enough to price a pending payment that completes later (Story 4.8).
        val purgeIntents = koin.get<PurgeOldPurchaseIntents>()
        scope.launch { purgeIntents() }
        // Story 4.10: the grant ledger is replayed (record, consume, settle) once the user has unlocked; every unlock signal
        // (UnlockSignals) and MainActivity start replay it again. Never on the wake path: the snooze started at its commit.
        val replayLedger = koin.get<ReplayGrantLedger>()
        scope.launch { replayLedger() }
        // The settled markers that refuse a token twice are kept 30 days, then deleted.
        val purchaseLedger = koin.get<PurchaseLedger>()
        scope.launch { purchaseLedger.purgeSettled() }
        // The price refresh jobs (Story 4.3): enqueued off the main thread, so an alarm's cold start never waits for
        // WorkManager; locked (Direct Boot) they wait for the unlock signals, which call the scheduler again.
        val prices = koin.get<PriceRefreshScheduler>()
        scope.launch { prices.start() }
        // Story 6.8: the weekly summary work is kept (or enqueued at the next Sunday 19:00) the same way: never replaced at a
        // start, so a run WorkManager started this process for is not cancelled; locked, the unlock signals start it.
        val weeklySummary = koin.get<WeeklySummaryScheduler>()
        scope.launch { weeklySummary.start() }
        // Story 4.11: Play's purchase updates are followed for the life of the process, and a recovery query runs once the
        // user has unlocked and a restore entry point (WakeService, MainActivity, WakeActivity) has restored the session.
        val coordinator = koin.get<PurchaseCoordinator>()
        coordinator.start()
        coordinator.onAppResumed()
        // With no session left in runtime.db (nothing, an unreadable row or a stored Idle), an alarm volume a crashed session
        // saved is put back (AD-5). Only a read: the session itself is restored by WakeService, MainActivity or
        // WakeActivity (Story 2.1).
        val store = koin.get<ActiveSessionStore>()
        val runtime = koin.get<WakeRuntime>()
        scope.launch {
            if (store.load().valueOrNull()?.holdsNoSession() == true) runtime.restoreVolumeIfIdle()
        }
    }
}

/** Nothing to take over: no row, an unreadable one or a stored Idle (restoring it runs no entry effect). */
private fun StoredSession.holdsNoSession(): Boolean =
    this == StoredSession.Empty || this is StoredSession.Unreadable || this == StoredSession.Found(SessionState.Idle)

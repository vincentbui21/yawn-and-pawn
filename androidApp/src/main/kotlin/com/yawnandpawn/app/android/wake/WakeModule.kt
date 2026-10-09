package com.yawnandpawn.app.android.wake

import android.media.AudioManager
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.call.AudioModeCallState
import com.yawnandpawn.app.android.call.CallDetector
import com.yawnandpawn.app.android.call.CallState
import com.yawnandpawn.app.android.call.StuckCallGuard
import com.yawnandpawn.app.android.crash.CrashlyticsSink
import com.yawnandpawn.app.android.crash.FirebaseCrashReporter
import com.yawnandpawn.app.android.crash.isFirebaseConfigured
import com.yawnandpawn.app.android.sound.LibrarySoundResolver
import com.yawnandpawn.app.core.billing.BillingCountry
import com.yawnandpawn.app.core.billing.DisplayPrices
import com.yawnandpawn.app.core.billing.LivePriceSource
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.billing.PurchaseCoordinator
import com.yawnandpawn.app.core.billing.PurchaseLedger
import com.yawnandpawn.app.core.billing.ReplayGrantLedger
import com.yawnandpawn.app.core.crash.CrashReporter
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.sound.SoundPreview
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings of the wake runtime (Story 1.14), included by `appModule`: one player, the vibrator, the notification,
 * the service starter and the runtime itself, which `appModule` binds as the engine's `EffectRunner`. The runtime reads
 * the engine's state lazily (the engine depends on it). The crash reporter is Crashlytics when configured (Story 1.19).
 * The player resolves sounds through the sound library (`LibrarySoundResolver`, Story 1.17).
 *
 * A function, not a shared `val`: each call has its own definitions, so a test can load a fresh runtime over the app's
 * (a Koin module object keeps its single instances).
 */
fun wakeModule(): Module =
    module {
        // Crashlytics when the app has a Firebase configuration (Story 1.19), else the no-op reporter that only logs.
        single<CrashReporter> {
            val context = androidContext()
            if (isFirebaseConfigured(context)) FirebaseCrashReporter(CrashlyticsSink(context), get()) else NoOpCrashReporter(get())
        }
        // Calls (Story 2.7): the audio mode only, never telephony; the adapter runs while the wake service or a ring does.
        // The runtime and the adapter share one StuckCallGuard (the 30-minute cap on a call pause).
        single<CallState> { AudioModeCallState(androidContext()) }
        single { StuckCallGuard(get()) }
        single { CallDetector(get(), get(), get(), get<WakeScope>(), get(), get()) }
        single { WakeScope(get()) }
        single<PlaybackFactory> { MediaPlayerPlaybackFactory(androidContext()) }
        single<SoundResolver> { LibrarySoundResolver() }
        single { AlarmVolume(androidContext(), get()) }
        single { AlarmVibrator(androidContext()) }
        single { WakeNotifier(androidContext(), get()) }
        single { WakeServiceStarter(androidContext(), get()) }
        // Device test round 1: the receiver waits for the service's start, and the ring start is timed in the log.
        single { WakeServiceStarts() }
        single { WakeTimings(now = { get<Clock>().now() }, logger = get()) }
        single {
            // A ring stops the Sound preview (Story 1.17); the preview is looked up then, as it depends on this player.
            val koin = this
            AndroidAlarmPlayer(
                get(),
                get(),
                get(),
                get(),
                get<WakeScope>(),
                get(),
                timings = get(),
                onRingStart = { koin.getOrNull<SoundPreview>()?.stop() },
                // Story 2.7: a focus change goes to the call adapter (a call takes focus); it never ducks the ring.
                focus =
                    AlarmAudioFocus(androidContext().getSystemService(AudioManager::class.java)) { change ->
                        koin.getOrNull<CallDetector>()?.onFocusChange(change)
                    },
            )
        }
        single {
            val koin = this
            WakeRuntime(
                outputs = WakeOutputs(get(), get(), get(), get(), get(), get()),
                starter = get(),
                scope = get<WakeScope>(),
                logger = get(),
                now = { TimeSnapshot.of(get(), get(), get()) },
                session = { get<SessionEngine>().state.value },
                timings = get(),
                // AD-2 InitBilling (Story 2.4): looked up when it runs, as UnlockSignals depends on the engine.
                onInitBilling = { get<UnlockSignals>().initialiseAfterUnlock() },
                calls = get<StuckCallGuard>(),
                // Story 2.7: every ring start makes the call adapter follow it (looked up then: it depends on the runtime).
                onRing = { koin.getOrNull<CallDetector>()?.follow() },
                // Story 4.10: the payment of a paid snooze is settled on the app scope, never inside the engine's step.
                onConsume = { token -> koin.get<ApplicationScope>().launch { koin.get<PurchaseLedger>().settle(token) } },
                // Story 4.11: the billing orchestration (looked up then: it depends on the engine, which depends on this).
                onLaunchBilling = { effect -> koin.get<PurchaseCoordinator>().onLaunchBilling(effect) },
                onKeyguardDismiss = { effect -> koin.get<PurchaseCoordinator>().onKeyguardDismiss(effect) },
            )
        }
        // The first unlock after a boot (Story 2.4): UserUnlocked, billing and crash reporting.
        single {
            val koin = this
            // Story 4.10: every unlock signal replays the grant ledger (looked up then: the ledger is in appModule).
            UnlockSignals(get(), get(), get(), get(), get(), get(), replayLedger = {
                koin.get<ApplicationScope>().launch { koin.get<ReplayGrantLedger>()() }
            })
        }
        includes(confirmSheetModule())
    }

/**
 * The snooze confirm sheet (Story 4.13), included by [wakeModule]: display prices from Story 4.3's price cache, "Use it"
 * and "Not now" through Story 4.11's coordinator. Until the Play adapter (4.12) binds them, no live price and no billing
 * country is known.
 */
private fun confirmSheetModule(): Module =
    module {
        single<LivePriceSource> { LivePriceSource.None }
        // Its own app-wide scope (as the coordinator's): it follows the cache for the life of the process, so it is not among
        // the app start's finite jobs.
        single<DisplayPrices> { CatalogDisplayPrices(get<PriceCatalog>(), get(), ApplicationScope(get())) }
        single<BillingCountry> { BillingCountry.None }
        single<ReuseChoices> {
            val koin = this
            ReuseChoices.of { koin.get<PurchaseCoordinator>() }
        }
        single { ConfirmSheetHost(get(), get(), get(), get(), get(), get(), get<ApplicationScope>(), get()) }
    }

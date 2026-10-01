package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.core.crash.CrashReporter
import com.yawnandpawn.app.core.session.RandomSeedSource
import com.yawnandpawn.app.core.session.SeedSource
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.time.TimeSnapshot
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings of the wake runtime (Story 1.14), included by `appModule`: one player, the vibrator, the notification,
 * the service starter and the runtime itself, which `appModule` binds as the engine's `EffectRunner`. The runtime reads
 * the engine's state lazily (the engine depends on it). Crashlytics replaces the reporter in Story 1.19, the sound
 * library the resolver in Story 1.17.
 *
 * A function, not a shared `val`: each call has its own definitions, so a test can load a fresh runtime over the app's
 * (a Koin module object keeps its single instances).
 */
fun wakeModule(): Module =
    module {
        single<CrashReporter> { NoOpCrashReporter(get()) }
        single<SeedSource> { RandomSeedSource() }
        single { WakeScope(get()) }
        single<PlaybackFactory> { MediaPlayerPlaybackFactory(androidContext()) }
        single<SoundResolver> { DefaultOnlySoundResolver }
        single { AlarmVolume(androidContext(), get()) }
        single { AlarmVibrator(androidContext()) }
        single { WakeNotifier(androidContext(), get()) }
        single { WakeServiceStarter(androidContext(), get()) }
        single { AndroidAlarmPlayer(get(), get(), get(), get(), get<WakeScope>(), get()) }
        single {
            WakeRuntime(
                outputs = WakeOutputs(get(), get(), get(), get(), get(), get()),
                starter = get(),
                scope = get<WakeScope>(),
                logger = get(),
                now = { TimeSnapshot.of(get(), get(), get()) },
                session = { get<SessionEngine>().state.value },
            )
        }
    }

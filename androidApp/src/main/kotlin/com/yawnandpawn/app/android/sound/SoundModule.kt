package com.yawnandpawn.app.android.sound

import com.yawnandpawn.app.android.wake.AndroidAlarmPlayer
import com.yawnandpawn.app.core.sound.SoundLibrary
import com.yawnandpawn.app.core.sound.SoundPreview
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings of the sound library (Story 1.17), included by `appModule`: the phone's alarm ringtones and the Sound
 * picker's preview player, which shares the alarm volume with the ring and keeps out of it. The player's
 * `SoundResolver` (`LibrarySoundResolver`) is bound in `wakeModule`. A function, like `wakeModule`, so a test can load
 * fresh instances.
 */
fun soundModule(): Module =
    module {
        single<SoundLibrary> { AndroidSoundLibrary(PlatformRingtoneSource(androidContext()), get()) }
        single<SoundPreview> {
            val player = get<AndroidAlarmPlayer>()
            AndroidSoundPreview(androidContext(), get(), get(), ringing = { player.isRinging }, logger = get())
        }
    }

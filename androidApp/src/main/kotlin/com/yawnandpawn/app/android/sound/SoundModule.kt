package com.yawnandpawn.app.android.sound

import com.yawnandpawn.app.android.wake.AndroidAlarmPlayer
import com.yawnandpawn.app.core.sound.SoundImporter
import com.yawnandpawn.app.core.sound.SoundLibrary
import com.yawnandpawn.app.core.sound.SoundPreview
import com.yawnandpawn.app.data.sound.SoundFiles
import com.yawnandpawn.app.ui.sound.SoundFilePicker
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings of the sound library (Story 1.17), included by `appModule`: the phone's alarm ringtones and the Sound
 * picker's preview player, which shares the alarm volume with the ring and keeps out of it, and (Story 7.4) the document
 * picker and the importer of the user's own files (their rows and `sounds/` folder come from `dataModule`). The player's
 * `SoundResolver` (`LibrarySoundResolver`) is bound in `wakeModule`. A function, like `wakeModule`, so a test can load
 * fresh instances.
 */
fun soundModule(): Module =
    module {
        single<SoundLibrary> {
            val sounds = get<SoundFiles>()
            AndroidSoundLibrary(PlatformRingtoneSource(androidContext()), get(), findFile = sounds::find)
        }
        // Story 7.4: the user's own files, picked with the system document picker (no permission) and copied in.
        single { AndroidSoundFilePicker(get()) }
        single<SoundFilePicker> { get<AndroidSoundFilePicker>() }
        single<SoundImporter> {
            AndroidSoundImporter(
                documents = ContentResolverDocuments(androidContext()),
                probe = MetadataAudioProbe(),
                files = get(),
                repository = get(),
                ids = get(),
                clock = get(),
                logger = get(),
            )
        }
        single<SoundPreview> {
            val player = get<AndroidAlarmPlayer>()
            AndroidSoundPreview(androidContext(), get(), get(), ringing = { player.isRinging }, logger = get())
        }
    }

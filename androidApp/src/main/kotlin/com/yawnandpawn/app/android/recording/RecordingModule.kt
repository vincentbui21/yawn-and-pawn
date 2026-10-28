package com.yawnandpawn.app.android.recording

import android.app.Application
import android.media.AudioManager
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.android.wake.AndroidAlarmPlayer
import com.yawnandpawn.app.core.recording.AudioRecorder
import com.yawnandpawn.app.core.recording.DeleteRecording
import com.yawnandpawn.app.core.recording.ReRecord
import com.yawnandpawn.app.core.recording.RecordingPreview
import com.yawnandpawn.app.core.recording.SaveRecording
import com.yawnandpawn.app.ui.recordings.MicrophonePermission
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings of the motivation messages (Story 7.1), included by `appModule`: the microphone recorder and the
 * recording use cases, and (Story 7.2) the Recordings preview player and the microphone permission. The repository and
 * the `recordings/` folder come from `dataModule`. The started-activity count is the app's own instance, registered for
 * the life of the process (another app context gets its own, registered on it). A function, like `soundModule`, so a
 * test can load fresh instances.
 */
fun recordingModule(): Module =
    module {
        single<AppForeground> {
            val context = androidContext()
            (context as? YawnAndPawnApp)?.startedActivities
                ?: StartedActivities().also { (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(it) }
        }
        single<RecordingFocus> { AndroidRecordingFocus(androidContext().getSystemService(AudioManager::class.java)) }
        single<AudioRecorder> { AndroidAudioRecorder(androidContext(), get(), get(), get(), get(), get(), get()) }
        factory { SaveRecording(get(), get(), get(), get(), get(), get()) }
        factory { ReRecord(get(), get(), get(), get()) }
        // Story 7.2: deleting a message also clears it from the alarms, under the alarm write lock.
        factory { DeleteRecording(get(), get(), get(), get(), get()) }
        // Story 7.2: the Recordings screen's preview (media stream, never during a ring) and the microphone permission.
        single<RecordingPreview> {
            val player = get<AndroidAlarmPlayer>()
            AndroidRecordingPreview(
                get(),
                ringing = { player.isRinging },
                logger = get(),
                // Its own focus, transient (not exclusive): music pauses while a message plays back.
                focus =
                    AndroidRecordingFocus(
                        androidContext().getSystemService(AudioManager::class.java),
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                    ),
            )
        }
        single { AndroidMicrophonePermission(androidContext(), get()) }
        single<MicrophonePermission> { get<AndroidMicrophonePermission>() }
    }

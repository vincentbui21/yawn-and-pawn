package com.yawnandpawn.app.android.recording

import android.app.Application
import android.media.AudioManager
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.core.recording.AudioRecorder
import com.yawnandpawn.app.core.recording.DeleteRecording
import com.yawnandpawn.app.core.recording.ReRecord
import com.yawnandpawn.app.core.recording.SaveRecording
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings of the motivation messages (Story 7.1), included by `appModule`: the microphone recorder and the
 * recording use cases. The repository and the `recordings/` folder come from `dataModule`. The started-activity count is
 * the app's own instance, registered for the life of the process (another app context gets its own, registered on it). A
 * function, like `soundModule`, so a test can load fresh instances.
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
        factory { DeleteRecording(get(), get(), get()) }
    }

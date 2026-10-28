package com.yawnandpawn.app.android.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.ui.recordings.MicrophoneAnswer
import com.yawnandpawn.app.ui.recordings.MicrophonePermission
import kotlinx.coroutines.CompletableDeferred

/**
 * The Android [MicrophonePermission] (Story 7.2), a copy of `AndroidCameraPermission`. The system dialog is launched by
 * the screen in front: `MainActivity` attaches its launcher ([attach]) while it is started and hands the dialog's answer
 * to [onResult], with `blocked` when `shouldShowRequestPermissionRationale` is false after a "no" (the dialog will not
 * show again). With no screen attached nothing is asked. Only the Recordings screen's record button asks.
 */
class AndroidMicrophonePermission(
    private val context: Context,
    private val logger: Logger,
) : MicrophonePermission {
    @Volatile
    private var launcher: (() -> Unit)? = null

    @Volatile
    private var pending: CompletableDeferred<MicrophoneAnswer>? = null

    /** The screen in front can show the dialog through [launch] (the newest attach wins). */
    @Synchronized
    fun attach(launch: () -> Unit) {
        launcher = launch
    }

    /** The screen that attached [launch] stopped; a newer screen's launcher stays. A request waiting on it is denied. */
    @Synchronized
    fun detach(launch: () -> Unit) {
        if (launcher === launch) {
            launcher = null
            pending?.complete(MicrophoneAnswer.Denied)
            pending = null
        }
    }

    /** The dialog's answer (also the immediate answer after "Don't ask again"); [blocked] when it cannot show again. */
    @Synchronized
    fun onResult(
        granted: Boolean,
        blocked: Boolean = false,
    ) {
        pending?.complete(
            when {
                granted -> MicrophoneAnswer.Granted
                blocked -> MicrophoneAnswer.Blocked
                else -> MicrophoneAnswer.Denied
            },
        )
        pending = null
    }

    override fun isGranted(): Boolean = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override suspend fun request(): MicrophoneAnswer {
        val answer = if (isGranted()) null else dialog()
        if (answer == null && !isGranted()) logger.log(LogEvent.OperationFailed("request microphone permission", "no screen in front"))
        return answer?.await() ?: if (isGranted()) MicrophoneAnswer.Granted else MicrophoneAnswer.Denied
    }

    /** The answer of the dialog shown now (one at a time: a second request waits on the same one); null with no screen. */
    @Synchronized
    private fun dialog(): CompletableDeferred<MicrophoneAnswer>? {
        val launch = launcher ?: return null
        return pending ?: CompletableDeferred<MicrophoneAnswer>().also {
            pending = it
            launch()
        }
    }

    override fun openSettings() {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

package com.yawnandpawn.app.android.qr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.ui.qr.CameraAnswer
import com.yawnandpawn.app.ui.qr.CameraPermission
import kotlinx.coroutines.CompletableDeferred

/**
 * The Android [CameraPermission] (Story 3.10). The system dialog is launched by the screen in front: `MainActivity`
 * attaches its launcher ([attach]) while it is started and hands the dialog's answer to [onResult]. With no screen
 * attached nothing is asked. The wake screen never asks: a missing permission there is "Camera isn't available.".
 *
 * Story 5.4: [requestAnswer] tells a refusal the dialog can still change apart from a blocked one. `MainActivity` reads
 * `shouldShowRequestPermissionRationale` after a "no": false then means the system will not show the dialog again
 * ("Don't ask again", a second denial, or the dialog never showed). A first dialog dismissed without an answer also
 * reads false on API 30+, so it counts as blocked too (the app details page, where the camera can still be allowed).
 */
class AndroidCameraPermission(
    private val context: Context,
    private val logger: Logger,
) : CameraPermission {
    @Volatile
    private var launcher: (() -> Unit)? = null

    @Volatile
    private var pending: CompletableDeferred<CameraAnswer>? = null

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
            pending?.complete(CameraAnswer.Denied)
            pending = null
        }
    }

    /**
     * The dialog's answer (also the immediate answer after "Don't ask again"); [blocked] when a "no" leaves the dialog
     * unable to show again (`shouldShowRequestPermissionRationale` false after it).
     */
    @Synchronized
    fun onResult(
        granted: Boolean,
        blocked: Boolean = false,
    ) {
        pending?.complete(
            when {
                granted -> CameraAnswer.Granted
                blocked -> CameraAnswer.Blocked
                else -> CameraAnswer.Denied
            },
        )
        pending = null
    }

    override fun isGranted(): Boolean = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override suspend fun request(): Boolean = requestAnswer() == CameraAnswer.Granted

    override suspend fun requestAnswer(): CameraAnswer {
        val answer = if (isGranted()) null else dialog()
        if (answer == null && !isGranted()) logger.log(LogEvent.OperationFailed("request camera permission", "no screen in front"))
        return answer?.await() ?: if (isGranted()) CameraAnswer.Granted else CameraAnswer.Denied
    }

    /** The answer of the dialog shown now (one at a time: a second request waits on the same one); null with no screen. */
    @Synchronized
    private fun dialog(): CompletableDeferred<CameraAnswer>? {
        val launch = launcher ?: return null
        return pending ?: CompletableDeferred<CameraAnswer>().also {
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

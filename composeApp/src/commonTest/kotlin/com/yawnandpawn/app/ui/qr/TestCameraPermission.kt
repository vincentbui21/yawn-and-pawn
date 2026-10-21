package com.yawnandpawn.app.ui.qr

/**
 * A camera permission under test control (Story 3.10): [answer] is what the dialog answers; with [blocked] a "no" is
 * one the dialog cannot change any more ([CameraAnswer.Blocked], Story 5.4).
 */
class TestCameraPermission(
    var granted: Boolean = true,
    private val answer: Boolean = granted,
    private val blocked: Boolean = false,
) : CameraPermission {
    var requests = 0
        private set

    var settingsOpened = 0
        private set

    override fun isGranted(): Boolean = granted

    override suspend fun request(): Boolean {
        requests++
        granted = answer
        return answer
    }

    override suspend fun requestAnswer(): CameraAnswer =
        when {
            request() -> CameraAnswer.Granted
            blocked -> CameraAnswer.Blocked
            else -> CameraAnswer.Denied
        }

    override fun openSettings() {
        settingsOpened++
    }
}

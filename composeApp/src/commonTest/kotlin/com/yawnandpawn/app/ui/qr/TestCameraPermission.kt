package com.yawnandpawn.app.ui.qr

/** A camera permission under test control (Story 3.10): [answer] is what the dialog answers. */
class TestCameraPermission(
    var granted: Boolean = true,
    private val answer: Boolean = granted,
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

    override fun openSettings() {
        settingsOpened++
    }
}

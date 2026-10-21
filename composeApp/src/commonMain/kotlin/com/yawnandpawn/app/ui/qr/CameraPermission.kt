package com.yawnandpawn.app.ui.qr

/**
 * The `CAMERA` permission (FR-ONB-2, Story 3.10). It is asked only when the user picks QR/Barcode (the Check picker) or
 * opens QR registration: never at app start, never for another check, and never on the wake screen.
 */
interface CameraPermission {
    fun isGranted(): Boolean

    /**
     * Shows the system dialog from the screen in front and returns whether the permission is granted. After "Don't ask
     * again" the system answers at once without a dialog, which is handled the same way as a denial. Without a screen in
     * front nothing is asked and the answer is false.
     */
    suspend fun request(): Boolean

    /**
     * [request], telling a denial the user can still change in the dialog ([CameraAnswer.Denied]) apart from one the
     * system answered without it ([CameraAnswer.Blocked]: "Don't ask again", or denied twice). The reliability checklist
     * opens the app details at once on Blocked (Story 5.4); without that knowledge every "no" is Denied.
     */
    suspend fun requestAnswer(): CameraAnswer = if (request()) CameraAnswer.Granted else CameraAnswer.Denied

    /** "Fix": opens the app's page in the system settings, from a user tap only. */
    fun openSettings()
}

/** The answer of a camera permission request ([CameraPermission.requestAnswer]). */
enum class CameraAnswer {
    Granted,

    /** Refused; the dialog can still be shown next time. */
    Denied,

    /** Refused and the dialog will not show again: only the app details page can grant it. */
    Blocked,
}

/**
 * What picking QR/Barcode in the Check picker does about the camera: granted already, or granted in the dialog asked now,
 * selects the card; denied (also "Don't ask again") leaves it unselected and shows "Camera isn't available." with "Fix".
 *
 * The editor's Check picker calls this when QR/Barcode is ticked (`AlarmEditorViewModel`), and shows
 * `CheckPickerUiState.cameraUnavailable` when it returns false.
 */
suspend fun CameraPermission.allowsCameraCheck(): Boolean = isGranted() || request()

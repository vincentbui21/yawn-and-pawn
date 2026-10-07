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

    /** "Fix": opens the app's page in the system settings, from a user tap only. */
    fun openSettings()
}

/**
 * What picking QR/Barcode in the Check picker does about the camera: granted already, or granted in the dialog asked now,
 * selects the card; denied (also "Don't ask again") leaves it unselected and shows "Camera isn't available." with "Fix".
 *
 * The editor's Check picker calls this when QR/Barcode is ticked (`AlarmEditorViewModel`), and shows
 * `CheckPickerUiState.cameraUnavailable` when it returns false.
 */
suspend fun CameraPermission.allowsCameraCheck(): Boolean = isGranted() || request()

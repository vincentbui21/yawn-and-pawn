package com.yawnandpawn.app.ui.recordings

/**
 * The `RECORD_AUDIO` permission (Story 7.2, copied from `CameraPermission`). It is asked only by the first record tap on
 * the Recordings screen: never at app start, never in onboarding, never on the wake screen.
 */
interface MicrophonePermission {
    fun isGranted(): Boolean

    /**
     * Shows the system dialog from the screen in front and returns the answer: [MicrophoneAnswer.Blocked] when the
     * system answers without the dialog ("Don't ask again", or denied twice). Without a screen in front nothing is asked
     * and the answer is [MicrophoneAnswer.Denied].
     */
    suspend fun request(): MicrophoneAnswer

    /** "Fix": opens the app's page in the system settings, from a user tap only. */
    fun openSettings()
}

/** The answer of a microphone permission request ([MicrophonePermission.request]). */
enum class MicrophoneAnswer {
    Granted,

    /** Refused; the dialog can still be shown next time. */
    Denied,

    /** Refused and the dialog will not show again: only the app details page can grant it. */
    Blocked,
}

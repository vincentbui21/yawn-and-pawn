package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.time.TimeSnapshot

/**
 * The Check screen for [state] when its current entry is a QR/Barcode check (Story 3.10), else null. Pure, like
 * [mathCheckUiState]: the same grace header, footer snooze and note ([wakeNote]).
 * - "Scan your code" and the `viewfinder` while [cameraAvailable]; without the camera (permission missing, CameraX could
 *   not start, or Story 3.11's watchdog and camera errors) "Camera isn't available. Pick a fallback check." instead.
 * - [input] follows the engine's position: more failed attempts on the entry (a different code) set its wrong flag,
 *   which shows "That's a different code. Scan your registered one." with an error haptic per failed attempt.
 * - [torchOn] is the torch toggle, UI only.
 *
 * `showFallbackLink` is set by the wake screen from Story 3.9's policy (`WakeCheck`): once the camera was unavailable on
 * the entry it asks with reason `CameraUnavailable`, so the link shows at once and stays (Story 3.11).
 */
fun qrCheckUiState(
    state: SessionState,
    availability: SnoozeAvailability,
    now: TimeSnapshot,
    input: CheckInput,
    cameraAvailable: Boolean,
    torchOn: Boolean = false,
    priceOf: PriceLookup = NoPrices,
): CheckUiState? {
    val session = (state as? SessionState.Grace)?.session ?: (state as? SessionState.Loud)?.session
    val run = session?.usableRun()?.takeIf { it.currentEntry?.type == CheckType.QrBarcode }
    if (session == null || run == null) return null
    return CheckUiState(
        grace = graceState(state, session, now),
        content =
            CheckContent.QrBarcode(
                cameraAvailable = cameraAvailable,
                wrongCode = input.wrong,
                torchOn = torchOn,
                wrongAttempts = run.failedAttempts,
            ),
        snooze = snoozeOffer(availability, priceOf),
        note = wakeNote(session),
    )
}

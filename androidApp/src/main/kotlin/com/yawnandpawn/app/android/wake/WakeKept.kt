package com.yawnandpawn.app.android.wake

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.ui.qr.CodeScanner

/**
 * What the wake screen keeps when the system recreates it during a ring (Story 3.11 review): a rotation, a font-scale
 * change, or auto dark mode flipping at sunrise while the alarm rings. It keeps the QR check ([qr]: the camera's latched
 * fallback link and monitor, and the torch switch) and whether the Fallback check picker is open ([pickerOpen]). It
 * holds no screen: the scanner, clock and logger are app singletons. A new wake screen (the next alarm, or after the
 * process died) starts fresh.
 */
internal class WakeKept(
    val qr: WakeQr,
    val pickerOpen: MutableState<Boolean> = mutableStateOf(false),
) : ViewModel() {
    companion object {
        /** Makes the [WakeKept] of a wake screen, once per screen and kept across its recreation. */
        fun factory(
            scanner: CodeScanner,
            monotonicClock: MonotonicClock,
            logger: Logger,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") // The only model this factory makes.
                override fun <T : ViewModel> create(modelClass: Class<T>): T = WakeKept(WakeQr(scanner, monotonicClock, logger)) as T
            }
    }
}

package com.yawnandpawn.app.core.net

import kotlinx.coroutines.flow.Flow

/**
 * Whether the phone can reach the internet (Story 4.7): snooze is unavailable offline, and the price refresh at session
 * start waits for it. The Android adapter reads the default network (validated internet); a captive portal reads as
 * offline. Play itself can still fail while this says online, so it is a hint, never a promise.
 */
fun interface Connectivity {
    /** The current value first, then every change (no repeats). */
    fun observeOnline(): Flow<Boolean>
}

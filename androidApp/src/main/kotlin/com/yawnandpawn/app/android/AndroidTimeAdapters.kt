package com.yawnandpawn.app.android

import android.content.ContentResolver
import android.os.SystemClock
import android.provider.Settings
import com.yawnandpawn.app.core.time.BootCounter
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import kotlinx.datetime.TimeZone

// The only package allowed to read the system clocks directly (detekt NoDirectTimeAccess, AD-3).

/** Milliseconds since boot including deep sleep. */
class AndroidMonotonicClock : MonotonicClock {
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
}

/**
 * `Settings.Global.BOOT_COUNT` (API 24+, never negative). If a device does not provide it (or it cannot be read),
 * [MISSING_BOOT_COUNT] every boot (Story 2.2): `Deadline` then tells a reboot by the elapsed clock going back below
 * the time the deadline was made at. An identity derived from the wall clock would change with every clock change,
 * and a clock change must never end or shorten a session (FR-SES-5).
 */
class AndroidBootCounter(
    private val contentResolver: ContentResolver,
) : BootCounter {
    override fun bootCount(): Int =
        Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, MISSING_BOOT_COUNT).takeIf { it >= 0 } ?: MISSING_BOOT_COUNT

    companion object {
        /** The boot count of a device without `BOOT_COUNT`: negative, so it never equals a real one. */
        const val MISSING_BOOT_COUNT = -1
    }
}

/** The system default zone, read on every call so a zone change is seen at once. */
class AndroidTimeZoneProvider : TimeZoneProvider {
    override fun current(): TimeZone = TimeZone.currentSystemDefault()
}

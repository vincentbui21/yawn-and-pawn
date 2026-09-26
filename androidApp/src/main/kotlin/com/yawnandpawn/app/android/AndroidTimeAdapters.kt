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
 * `Settings.Global.BOOT_COUNT` (API 24+, never negative). If a device does not provide it, a per-boot identity
 * is used instead: the boot instant (wall millis minus [SystemClock.elapsedRealtime]) in whole minutes, mapped
 * to a negative Int so it can never equal a real boot count. It stays the same within a boot and changes on
 * reboot. A wall-clock change of a minute or more also changes it, which makes a `Deadline` fall back to wall
 * time: safe, only less precise.
 */
class AndroidBootCounter(
    private val contentResolver: ContentResolver,
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) : BootCounter {
    override fun bootCount(): Int {
        val count = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, MISSING)
        return if (count >= 0) count else bootInstantIdentity()
    }

    private fun bootInstantIdentity(): Int {
        val bootMinute = Math.floorDiv(wallMillis() - elapsedMillis(), MILLIS_PER_MINUTE)
        // 1..Int.MAX_VALUE, negated: always below 0, where real boot counts never are.
        return -(Math.floorMod(bootMinute, Int.MAX_VALUE.toLong()) + 1).toInt()
    }

    private companion object {
        const val MISSING = -1
        const val MILLIS_PER_MINUTE = 60_000L
    }
}

/** The system default zone, read on every call so a zone change is seen at once. */
class AndroidTimeZoneProvider : TimeZoneProvider {
    override fun current(): TimeZone = TimeZone.currentSystemDefault()
}

package com.yawnandpawn.app.android

import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.core.time.BootCounter
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.stopApp
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AndroidTimeAdaptersTest {
    private val app = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()

    @After
    fun tearDown() {
        stopApp()
    }

    @Test
    fun `Koin resolves every time port to its Android adapter`() {
        val koin = GlobalContext.get()

        assertSame(kotlin.time.Clock.System, koin.get<Clock>())
        assertIs<AndroidMonotonicClock>(koin.get<MonotonicClock>())
        assertIs<AndroidBootCounter>(koin.get<BootCounter>())
        assertIs<AndroidTimeZoneProvider>(koin.get<TimeZoneProvider>())
    }

    @Test
    fun `the monotonic clock reads elapsedRealtime`() {
        val clock = AndroidMonotonicClock()
        val before = clock.elapsedMillis()

        SystemClock.sleep(1_500)

        assertEquals(SystemClock.elapsedRealtime(), clock.elapsedMillis())
        assertEquals(1_500, clock.elapsedMillis() - before)
    }

    @Test
    fun `the boot counter reads Settings_Global BOOT_COUNT`() {
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 5)

        assertEquals(5, AndroidBootCounter(app.contentResolver).bootCount())
    }

    @Test
    fun `without BOOT_COUNT the boot counter is a negative identity that is stable within a boot`() {
        assertEquals(-1, Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1), "precondition: setting missing")
        var wall = 1_800_000_000_000L
        var elapsed = 3_600_000L
        val counter = AndroidBootCounter(app.contentResolver, wallMillis = { wall }, elapsedMillis = { elapsed })
        val first = counter.bootCount()

        wall += 5 * 3_600_000L
        elapsed += 5 * 3_600_000L

        assertTrue(first < 0, "identity $first must be negative")
        assertEquals(first, counter.bootCount())
    }

    @Test
    fun `without BOOT_COUNT the boot counter changes when the boot instant changes`() {
        var wall = 1_800_000_000_000L
        var elapsed = 3_600_000L
        val counter = AndroidBootCounter(app.contentResolver, wallMillis = { wall }, elapsedMillis = { elapsed })
        val beforeReboot = counter.bootCount()

        // Reboot ten minutes later: elapsed restarts near 0 while the wall clock keeps running.
        wall += 10 * 60_000L
        elapsed = 5_000L

        val afterReboot = counter.bootCount()
        assertTrue(afterReboot < 0, "identity $afterReboot must be negative")
        assertNotEquals(beforeReboot, afterReboot)
    }

    @Test
    fun `without BOOT_COUNT the default sources are the system clocks`() {
        assertTrue(AndroidBootCounter(app.contentResolver).bootCount() < 0)
    }

    @Test
    fun `the time zone provider follows the system default zone on every call`() {
        val original = java.util.TimeZone.getDefault()
        try {
            val provider = AndroidTimeZoneProvider()
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Berlin"))
            assertEquals(TimeZone.of("Europe/Berlin"), provider.current())

            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
            assertEquals(TimeZone.of("America/New_York"), provider.current())
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }
}

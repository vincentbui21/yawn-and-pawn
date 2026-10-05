package com.yawnandpawn.app.android

import android.app.AlarmManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.android.wake.WakeAlarmFiredHandler
import com.yawnandpawn.app.android.wake.WakeServiceStarts
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.alarm.AlarmFiredHandler
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.alarm.AlarmScheduling
import com.yawnandpawn.app.core.alarm.RearmOnFire
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.stopApp
import com.yawnandpawn.app.testing.FakeClock
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.util.TimeZone
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The real app (Koin graph, Room `app.db`, `AndroidAlarmScheduler`, both receivers) with a fake wall clock and the
 * system default zone set to [zoneId]. The time zone port stays the real `AndroidTimeZoneProvider`, so a zone change
 * is a change of the system default, as on a device. [awaitWork] runs the main looper (broadcast delivery) and waits
 * for the receivers' `goAsync()` work. Call [close] in `@After`.
 */
internal class SchedulingApp(
    now: Instant,
    zoneId: String,
) {
    val app: YawnAndPawnApp = ApplicationProvider.getApplicationContext()
    val clock = FakeClock(now)
    val alarmManager: ShadowAlarmManager = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val scope = ApplicationScope(AndroidLogger())
    private val originalZone: TimeZone = TimeZone.getDefault()

    val koin: Koin
        get() = GlobalContext.get()

    val repository: AlarmRepository
        get() = koin.get()

    init {
        // The app-start rescheduleAll runs on the real clock over an empty database; let it finish so it cannot race.
        koin.get<ApplicationScope>().awaitChildren()
        setZone(zoneId)
        // AlarmScheduling and the handler are singles that captured the real clock at app start: rebuild them.
        loadKoinModules(
            module {
                single<Clock> { clock }
                single { scope }
                single<AlarmScheduler> { AndroidAlarmScheduler(androidContext(), get(), get(), get(), get()) }
                single { AlarmScheduling(get(), get(), get(), get(), get(), get()) }
                single { RearmOnFire(get(), get(), get(), get(), get(), get()) }
                // No service starts here (the tests read the start requests), so the receiver does not wait for one.
                single<AlarmFiredHandler> {
                    WakeAlarmFiredHandler(
                        get(),
                        get<RearmOnFire>(),
                        get(),
                        get(),
                        WakeServiceStarts(Duration.ZERO),
                    )
                }
            },
        )
    }

    fun setZone(zoneId: String) {
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
    }

    /** Delivers pending broadcasts and waits until every receiver's work has finished. */
    fun awaitWork() {
        shadowOf(Looper.getMainLooper()).idle()
        scope.awaitChildren()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The armed alarm clocks as request code to trigger time. */
    fun armed(): Map<Int, Long> = alarmManager.scheduledAlarms.associate { shadowOf(it.operation).requestCode to it.triggerAtTime }

    fun close() {
        TimeZone.setDefault(originalZone)
        stopApp()
    }
}

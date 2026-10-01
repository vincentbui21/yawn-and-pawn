package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoInexactAlarmTest {
    private val rule = NoInexactAlarm(Config.empty)

    private fun findings(code: String) = rule.lint("package com.yawnandpawn.app.android\n\n$code\n")

    private val banned =
        listOf(
            "set(AlarmManager.RTC_WAKEUP, at, operation)",
            "setExact(AlarmManager.RTC_WAKEUP, at, operation)",
            "setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)",
            "setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)",
            "setRepeating(AlarmManager.RTC_WAKEUP, at, 60_000L, operation)",
            "setInexactRepeating(AlarmManager.RTC_WAKEUP, at, AlarmManager.INTERVAL_DAY, operation)",
            "setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, operation)",
        )

    @Test
    fun `every inexact or windowed AlarmManager call is reported in a file that imports AlarmManager`() {
        banned.forEach { call ->
            val findings =
                findings(
                    """
                    import android.app.AlarmManager
                    import android.app.PendingIntent

                    fun arm(alarmManager: AlarmManager, at: Long, operation: PendingIntent) {
                        alarmManager.$call
                    }
                    """.trimIndent(),
                )

            assertEquals(1, findings.size, call)
            assertTrue(findings.single().message.contains("setAlarmClock"), call)
        }
    }

    @Test
    fun `a fully qualified AlarmManager, a star import or a callable reference is caught too`() {
        val qualified =
            findings(
                """
                fun arm(context: android.content.Context, operation: android.app.PendingIntent) {
                    context.getSystemService(android.app.AlarmManager::class.java).setExact(0, 1L, operation)
                }
                """.trimIndent(),
            )
        val star =
            findings(
                """
                import android.app.*

                fun arm(alarmManager: AlarmManager, operation: PendingIntent) = alarmManager.set(0, 1L, operation)
                """.trimIndent(),
            )
        val reference =
            findings(
                """
                import android.app.AlarmManager

                fun ref(alarmManager: AlarmManager) = alarmManager::setWindow
                """.trimIndent(),
            )

        assertEquals(listOf(1, 1, 1), listOf(qualified.size, star.size, reference.size))
    }

    @Test
    fun `inexact calls through AlarmManagerCompat are caught in a file that never names AlarmManager`() {
        val imported =
            findings(
                """
                import androidx.core.app.AlarmManagerCompat

                fun arm(context: Context, operation: PendingIntent) {
                    AlarmManagerCompat.setExactAndAllowWhileIdle(context.getSystemService(ALARM_SERVICE) as Manager, 0, 1L, operation)
                    AlarmManagerCompat.setAndAllowWhileIdle(alarms(context), 0, 1L, operation)
                    AlarmManagerCompat.setExact(alarms(context), 0, 1L, operation)
                }
                """.trimIndent(),
            )
        val qualified =
            findings(
                """
                fun arm(operation: PendingIntent) = androidx.core.app.AlarmManagerCompat.setExact(alarms(), 0, 1L, operation)
                """.trimIndent(),
            )
        val clockOnly =
            findings(
                """
                import androidx.core.app.AlarmManagerCompat

                fun arm(info: Info, operation: PendingIntent) = AlarmManagerCompat.setAlarmClock(alarms(), 1L, info, operation)
                """.trimIndent(),
            )

        assertEquals(listOf(3, 1, 0), listOf(imported.size, qualified.size, clockOnly.size))
    }

    @Test
    fun `setAlarmClock and cancel are allowed`() {
        val findings =
            findings(
                """
                import android.app.AlarmManager
                import android.app.PendingIntent

                fun arm(alarmManager: AlarmManager, info: AlarmManager.AlarmClockInfo, operation: PendingIntent) {
                    alarmManager.setAlarmClock(info, operation)
                    alarmManager.cancel(operation)
                    alarmManager.canScheduleExactAlarms()
                }
                """.trimIndent(),
            )

        assertEquals(0, findings.size)
    }

    @Test
    fun `calls named set in a file without AlarmManager are not reported`() {
        val findings =
            findings(
                """
                class FakeClock { fun set(millis: Long) = Unit }

                fun jump(clock: FakeClock, values: MutableList<Int>) {
                    clock.set(1L)
                    values.set(0, 1)
                }
                """.trimIndent(),
            )

        assertEquals(0, findings.size)
    }
}

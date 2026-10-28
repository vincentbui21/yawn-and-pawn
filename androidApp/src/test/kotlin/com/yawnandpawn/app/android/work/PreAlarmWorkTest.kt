package com.yawnandpawn.app.android.work

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Looper
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.ListenableFuture
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.prealarm.AndroidPreAlarmNotifier
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmScheduling
import com.yawnandpawn.app.core.alarm.PreAlarmNotifier
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.core.work.BackgroundWork
import com.yawnandpawn.app.core.work.PreAlarmJobs
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.FakeUserLockState
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Story 7.11 (FR-ALM-10, AD-17): the pre-alarm job on WorkManager's own test setup, with the real adapter, scheduling,
 * task and notifier over a pinned clock and zone. A daily 07:00 alarm (UTC) with "Offer to skip" on gets one unique
 * `pre-alarm-{id}` work 2 h before its next ring (REPLACE); its run posts "7:00 AM alarm" / "Rings in ..." with "Skip
 * this one" on the "Upcoming alarms" channel, and posts nothing when late or not permitted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class PreAlarmWorkTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val log = FakeLogger()

    // Tuesday 2027-03-09 01:00 UTC: the 07:00 ring is 6 h away, so the job waits 4 h.
    private val clock = FakeClock(Instant.parse("2027-03-09T01:00:00Z"))
    private val sevenOClock = Instant.parse("2027-03-09T07:00:00Z")
    private val alarm: Alarm =
        anAlarm(id = "alarm-1", time = LocalTime(7, 0), repeatDays = DayOfWeek.entries.toSet(), requestCode = 1_001)
            .copy(offerSkip = true)
    private val alarms = FakeAlarmRepository(listOf(alarm))

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration
                .Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        restartKoin(
            app,
            module {
                single<Logger> { log }
                single<UserLockState> { FakeUserLockState(unlocked = true) }
                // The real adapter instead of the tests' fake.
                single<BackgroundWork> { AndroidBackgroundWork(app, get(), get()) }
                single<Clock> { clock }
                single<TimeZoneProvider> { FakeTimeZoneProvider(TimeZone.UTC) }
                single<AlarmRepository> { alarms }
            },
        )
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    // WorkManager's test instance is process-wide: closed here, so no later test of this JVM finds its database open.
    @After
    fun tearDown() = WorkManagerTestInitHelper.closeWorkDatabase()

    private val koin get() = GlobalContext.get()
    private val scheduling: AlarmScheduling get() = koin.get()
    private val workManager: WorkManager get() = WorkManager.getInstance(app)
    private val notifications get() = app.getSystemService(NotificationManager::class.java)

    private fun infos(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(PreAlarmJobs.nameOf(alarm.id)).getBounded()

    private fun <T> ListenableFuture<T>.getBounded(): T = get(APP_WORK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)

    private fun pending(): WorkInfo = assertNotNull(infos().singleOrNull { !it.state.isFinished }, "one pending job: ${infos()}")

    private fun posted(): List<Notification> = shadowOf(notifications).allNotifications

    /** Schedules the job now, then runs it at [at] as WorkManager would once its delay passed. */
    private fun runAt(at: Instant): UUID {
        scheduling.sync(alarm)
        val id = pending().id
        clock.set(at)
        assertNotNull(WorkManagerTestInitHelper.getTestDriver(app)).setInitialDelayMet(id)
        awaitUntil("the run finished") {
            workManager
                .getWorkInfoById(id)
                .getBounded()
                ?.state
                ?.isFinished == true
        }
        return id
    }

    private fun awaitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + APP_WORK_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        fail("timed out waiting until $what")
    }

    @Test
    fun `the job waits until 2 h before the ring, needs no network and is replaced on each sync`() {
        scheduling.sync(alarm)
        val first = pending()
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        assertEquals(4.hours.inWholeMilliseconds, first.initialDelayMillis)
        assertEquals(NetworkType.NOT_REQUIRED, first.constraints.requiredNetworkType)

        clock.set(Instant.parse("2027-03-09T06:00:00Z"))
        scheduling.sync(alarm)

        // Inside the 2 h window it runs at once (the test executor runs it right away), replacing the first one.
        awaitUntil("the second job posted") { posted().isNotEmpty() }
        // REPLACE: the first job is cancelled (and may already be pruned).
        val replaced = workManager.getWorkInfoById(first.id).getBounded()?.state
        assertTrue(replaced == null || replaced == WorkInfo.State.CANCELLED, "REPLACE left $replaced")
        assertEquals("Rings in 1 h 0 min", posted().single().extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `switching Offer to skip off or the alarm off cancels the job`() {
        scheduling.sync(alarm)

        scheduling.sync(alarm.copy(offerSkip = false))
        assertTrue(infos().all { it.state == WorkInfo.State.CANCELLED })

        scheduling.sync(alarm)
        scheduling.sync(alarm.copy(enabled = false))
        assertTrue(infos().none { !it.state.isFinished })
    }

    @Test
    fun `the run posts the notification on Upcoming alarms with Skip this one, opening the app with the alarm id`() {
        runAt(sevenOClock - 2.hours)

        val notification = posted().single()
        val channel = assertNotNull(notifications.getNotificationChannel(AndroidPreAlarmNotifier.CHANNEL_ID))
        assertEquals("upcoming_alarms", notification.channelId)
        assertEquals("Upcoming alarms", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals("7:00 AM alarm", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Rings in 2 h 0 min", notification.extras.getString(Notification.EXTRA_TEXT))
        assertNotNull(notification.smallIcon)
        assertEquals(Notification.COLOR_DEFAULT, notification.color)
        val action = notification.actions.single()
        assertEquals("Skip this one", action.title)
        val skip = shadowOf(action.actionIntent)
        assertTrue(skip.isActivityIntent)
        assertTrue(skip.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals(MainActivity::class.java.name, skip.savedIntent.component?.className)
        assertEquals(alarm.id, skip.savedIntent.getStringExtra(MainActivity.EXTRA_SKIP_ALARM))
        assertNull(shadowOf(notification.contentIntent).savedIntent.getStringExtra(MainActivity.EXTRA_SKIP_ALARM), "a tap skips nothing")
        // One per alarm, under an id derived from its request code.
        assertNotNull(shadowOf(notifications).getNotification(AndroidPreAlarmNotifier.notificationIdOf(alarm.requestCode)))
    }

    @Test
    fun `under an hour it says the minutes`() {
        runAt(sevenOClock - 45.minutes)

        assertEquals("Rings in 45 min", posted().single().extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `a run at or after the occurrence posts nothing`() {
        runAt(sevenOClock + 1.minutes)

        assertEquals(emptyList(), posted())
    }

    @Test
    fun `without notifications nothing is posted and nothing asks for the permission`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        runAt(sevenOClock - 1.hours)

        assertEquals(emptyList(), posted())
        assertNull(shadowOf(app).nextStartedActivity, "no activity starts to ask")
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(false)
        assertEquals(false, koin.get<PreAlarmNotifier>().canPost())
    }

    @Test
    fun `a sync for a later ring removes the notification still showing, and so does the alarm switched off`() {
        runAt(sevenOClock - 1.hours)
        assertEquals(1, posted().size)

        // Skipped: the next ring is tomorrow's, whose window has not started.
        scheduling.sync(alarm.copy(skippedDate = LocalDate(2027, 3, 9)))
        assertEquals(emptyList(), posted())

        // At 06:00 the window has started: the job runs at once and posts again.
        scheduling.sync(alarm)
        assertEquals(1, posted().size)
        scheduling.sync(alarm.copy(enabled = false))
        assertEquals(emptyList(), posted())
    }

    private companion object {
        const val POLL_MILLIS = 20L
    }
}

package com.yawnandpawn.app.android.work

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.provider.Settings
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
import com.yawnandpawn.app.R
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.reminder.AndroidSkipReminderNotifier
import com.yawnandpawn.app.android.reminder.SkipReminderActionReceiver
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmScheduling
import com.yawnandpawn.app.core.alarm.UndoSkip
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.reminder.SetSkipReminder
import com.yawnandpawn.app.core.reminder.SkipReminderNotifier
import com.yawnandpawn.app.core.reminder.SkipReminderStore
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionJson
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.core.work.BackgroundWork
import com.yawnandpawn.app.core.work.SkipReminderJobs
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeSkipReminderStore
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.FakeUserLockState
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
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
 * 0.1.8 (FR-ALM-10, AD-17): the Skip reminder on WorkManager's own test setup, with the real adapter, scheduling, task,
 * notifier and action receiver over a pinned clock and zone. Daily 07:00 and 07:30 alarms (UTC) get one unique
 * `skip-reminder` work 2 h before Tuesday's first ring (REPLACE); its run posts "Up already?" / "Your 7:00 AM alarm rings
 * in ... Skip today's alarms?" with "Skip today's alarms" and "Keep" on the "Upcoming alarms" channel, and posts nothing
 * when late or not permitted. "Skip today's alarms" skips both without opening the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class SkipReminderWorkTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val log = FakeLogger()

    // Tuesday 2027-03-09 01:00 UTC: the 07:00 ring is 6 h away, so the job waits 4 h.
    private val clock = FakeClock(Instant.parse("2027-03-09T01:00:00Z"))
    private val sevenOClock = Instant.parse("2027-03-09T07:00:00Z")
    private val tuesday = LocalDate(2027, 3, 9)
    private val alarm: Alarm =
        anAlarm(id = "alarm-1", time = LocalTime(7, 0), repeatDays = DayOfWeek.entries.toSet(), requestCode = 1_001)
    private val later: Alarm =
        anAlarm(id = "alarm-2", time = LocalTime(7, 30), repeatDays = DayOfWeek.entries.toSet(), requestCode = 1_002)
    private val alarms = FakeAlarmRepository(listOf(alarm, later))
    private val store = FakeSkipReminderStore()
    private val sessionStore = FakeActiveSessionStore()
    private val history = FakeSessionHistoryRepository()

    /** How many Skipped rows may still be written; the next one fails (a half-done "Skip today's alarms"). */
    private var rowsLeft = Int.MAX_VALUE
    private val rows =
        object : SessionHistoryRepository by history {
            override suspend fun upsert(row: SessionHistoryRow): Outcome<Unit, DomainError> =
                if (rowsLeft-- > 0) history.upsert(row) else Outcome.Failure(DomainError.StorageFailure("disk"))
        }

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
                single<SkipReminderStore> { store }
                single<ActiveSessionStore> { sessionStore }
                single<SessionHistoryRepository> { rows }
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

    private fun infos(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(SkipReminderJobs.NAME).getBounded()

    private fun <T> ListenableFuture<T>.getBounded(): T = get(APP_WORK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)

    private fun pending(): WorkInfo = assertNotNull(infos().singleOrNull { !it.state.isFinished }, "one pending job: ${infos()}")

    private fun posted(): List<Notification> = shadowOf(notifications).allNotifications

    private fun sync() = runBlocking { scheduling.sync(alarm) }

    private fun Notification.title(): String? = extras.getString(Notification.EXTRA_TITLE)

    private fun Notification.text(): String? = extras.getString(Notification.EXTRA_TEXT)

    /** Schedules the job now, then runs it at [at] as WorkManager would once its delay passed. */
    private fun runAt(at: Instant): UUID {
        sync()
        val id = pending().id
        clock.set(at)
        assertNotNull(WorkManagerTestInitHelper.getTestDriver(app)).setInitialDelayMet(id)
        // The run chains the next date after itself (APPEND_OR_REPLACE), so it finishes as usual.
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
    fun `the job waits until 2 h before the first ring, needs no network, and inside the window a sync posts at once`() {
        sync()
        val first = pending()
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        assertEquals(4.hours.inWholeMilliseconds, first.initialDelayMillis)
        assertEquals(NetworkType.NOT_REQUIRED, first.constraints.requiredNetworkType)

        clock.set(Instant.parse("2027-03-09T06:00:00Z"))
        sync()

        assertEquals("Your 7:00 AM alarm rings in 1 h 0 min. Skip today's alarms?", posted().single().text())
        // Tuesday is reminded: the job now waits for Wednesday 05:00 (REPLACE).
        assertEquals(23.hours.inWholeMilliseconds, pending().initialDelayMillis)
        assertEquals(tuesday, store.prefs.lastRemindedDate)
    }

    @Test
    fun `the run posts Up already on Upcoming alarms with Skip today's alarms and Keep, and a tap opens Alarms`() {
        runAt(sevenOClock - 2.hours)

        val notification = posted().single()
        val channel = assertNotNull(notifications.getNotificationChannel(AndroidSkipReminderNotifier.CHANNEL_ID))
        assertEquals("upcoming_alarms", notification.channelId)
        assertEquals("Upcoming alarms", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals("Up already?", notification.title())
        assertEquals("Your 7:00 AM alarm rings in 2 h 0 min. Skip today's alarms?", notification.text())
        assertNotNull(notification.smallIcon)
        assertEquals(Notification.COLOR_DEFAULT, notification.color)
        assertNotNull(shadowOf(notifications).getNotification(AndroidSkipReminderNotifier.NOTIFICATION_ID))

        val (skip, keep) = notification.actions.toList()
        assertEquals("Skip today's alarms", skip.title)
        assertEquals("Keep", keep.title)
        for (action in listOf(skip, keep)) {
            val pending = shadowOf(action.actionIntent)
            assertTrue(pending.isBroadcastIntent, "never opens the app")
            assertTrue(pending.flags and PendingIntent.FLAG_IMMUTABLE != 0)
            assertEquals(SkipReminderActionReceiver::class.java.name, pending.savedIntent.component?.className)
        }
        assertEquals(SkipReminderActionReceiver.ACTION_SKIP, shadowOf(skip.actionIntent).savedIntent.action)
        assertEquals("2027-03-09", shadowOf(skip.actionIntent).savedIntent.getStringExtra(SkipReminderActionReceiver.EXTRA_DATE))
        assertEquals(SkipReminderActionReceiver.ACTION_KEEP, shadowOf(keep.actionIntent).savedIntent.action)

        val tap = shadowOf(notification.contentIntent)
        assertTrue(tap.isActivityIntent)
        assertTrue(tap.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals(MainActivity::class.java.name, tap.savedIntent.component?.className)
        assertEquals(MainActivity.OPEN_ALARMS, tap.savedIntent.getStringExtra(MainActivity.EXTRA_OPEN_TAB))
    }

    @Test
    fun `under an hour it says the minutes, in the phone's 24-hour format`() {
        Settings.System.putString(app.contentResolver, Settings.System.TIME_12_24, "24")

        runAt(sevenOClock - 45.minutes)

        assertEquals("Your 07:00 alarm rings in 45 min. Skip today's alarms?", posted().single().text())
    }

    @Test
    fun `a run with 15 min or less left posts nothing`() {
        runAt(sevenOClock - 10.minutes)

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
        assertEquals(false, koin.get<SkipReminderNotifier>().canPost())
    }

    @Test
    fun `turning the setting off cancels the job and removes the reminder`() {
        runAt(sevenOClock - 1.hours)
        assertEquals(1, posted().size)

        // Settings is reached through MainActivity, which restores the session first.
        runBlocking { koin.get<SessionEngine>().restore() }
        assertEquals(Outcome.Success(Unit), runBlocking { koin.get<SetSkipReminder>()(false) })

        assertEquals(emptyList(), posted())
        assertTrue(infos().none { !it.state.isFinished })
    }

    @Test
    fun `Skip today's alarms skips both alarms without opening the app, and the notification says so`() {
        runAt(sevenOClock - 1.hours)
        val skip = posted().single().actions.first()

        skip.actionIntent.send()

        awaitUntil("both alarms skipped") { alarms.current.all { it.skippedDate == tuesday } }
        awaitUntil("the confirmation shows") { posted().singleOrNull()?.title() == "Today's alarms are skipped. No charge." }
        assertTrue(posted().single().actions.isNullOrEmpty())
        assertNull(shadowOf(app).nextStartedActivity, "the app is not opened")
    }

    @Test
    fun `Keep removes the reminder and changes nothing`() {
        runAt(sevenOClock - 1.hours)
        val keep = posted().single().actions.last()

        keep.actionIntent.send()

        awaitUntil("the reminder is gone") { posted().isEmpty() }
        assertTrue(alarms.current.all { it.skippedDate == null })
    }

    /** Sends [action]'s broadcast and waits until the receiver's work on the app scope is done. */
    private fun press(action: Notification.Action) {
        action.actionIntent.send()
        // The receiver's work runs on the app scope and resumes on the main looper: idled while waiting, never blocked.
        val appJob = assertNotNull(koin.get<ApplicationScope>().coroutineContext[Job])
        awaitUntil("the receiver finished") { appJob.children.none() }
    }

    /** A paid snooze that ends in 9 minutes, stored in runtime.db; the engine is not restored (a cold start). */
    private fun storeSnoozedSession() {
        val now = TimeSnapshot.of(koin.get(), koin.get(), koin.get())
        sessionStore.row = SessionJson.encode(SessionState.Snoozed(aSession().copy(snoozeEnd = Deadline.after(now, 9.minutes))))
    }

    @Test
    fun `the run chains the next date after itself, so it succeeds`() {
        val id = runAt(sevenOClock - 2.hours)

        assertEquals(WorkInfo.State.SUCCEEDED, workManager.getWorkInfoById(id).getBounded()?.state)
        assertEquals(24.hours.inWholeMilliseconds, pending().initialDelayMillis, "Wednesday 05:00")
    }

    @Test
    fun `posted the evening before a ring just after midnight, it says tomorrow's, and so does the confirmation`() {
        clock.set(Instant.parse("2027-03-09T23:30:00Z"))
        runBlocking {
            alarms.upsert(
                anAlarm(id = "night", time = LocalTime(1, 0), requestCode = 1_003, updatedAt = Instant.parse("2027-03-09T20:00:00Z")),
            )
        }

        sync()

        val reminder = posted().single()
        assertEquals("Your 1:00 AM alarm rings in 1 h 30 min. Skip tomorrow's alarms?", reminder.text())
        assertEquals("Skip tomorrow's alarms", reminder.actions.first().title)

        press(reminder.actions.first())

        assertEquals("Tomorrow's alarms are skipped. No charge.", posted().single().title())
    }

    @Test
    fun `a skip that fails half way keeps the reminder, logs it once, and a second press skips the rest`() {
        runAt(sevenOClock - 1.hours)
        rowsLeft = 1

        press(posted().single().actions.first())

        assertEquals(1, alarms.current.count { it.skippedDate == tuesday })
        assertEquals("Up already?", posted().single().title())
        assertEquals(1, log.events.count { it is LogEvent.OperationFailed && it.operation == "skip today's alarms" })

        rowsLeft = Int.MAX_VALUE
        press(posted().single().actions.first())
        assertTrue(alarms.current.all { it.skippedDate == tuesday })
        assertEquals("Today's alarms are skipped. No charge.", posted().single().title())
    }

    @Test
    fun `Don't skip removes the confirmation`() {
        runAt(sevenOClock - 1.hours)
        press(posted().single().actions.first())
        assertEquals("Today's alarms are skipped. No charge.", posted().single().title())

        assertEquals(Outcome.Success(true), runBlocking { koin.get<UndoSkip>()(alarm.id) })

        assertEquals(emptyList(), posted())
    }

    @Test
    fun `the update cleanup removes Story 7_11's pre-alarm notifications`() {
        val old = Notification.Builder(app, AndroidSkipReminderNotifier.CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_alarm).build()
        notifications.notify(AndroidSkipReminderNotifier.LEGACY_NOTIFICATION_ID_BASE + alarm.requestCode, old)

        runBlocking { scheduling.rescheduleAll() }

        assertNull(shadowOf(notifications).getNotification(AndroidSkipReminderNotifier.LEGACY_NOTIFICATION_ID_BASE + alarm.requestCode))
    }

    @Test
    fun `with a snooze stored and the engine not restored, the job posts nothing and the next date is scheduled`() {
        storeSnoozedSession()

        runAt(sevenOClock - 1.hours)

        assertEquals(emptyList(), posted())
        assertEquals(tuesday, store.prefs.lastRemindedDate)
        assertEquals(24.hours.inWholeMilliseconds - 1.hours.inWholeMilliseconds, pending().initialDelayMillis, "Wednesday 05:00")
    }

    @Test
    fun `the skip pressed with a snooze stored skips nothing, restores nothing and keeps the reminder`() {
        runAt(sevenOClock - 1.hours)
        storeSnoozedSession()
        val logged = log.events.size

        press(posted().single().actions.first())

        assertEquals(false, koin.get<SessionEngine>().restored.value)
        assertTrue(alarms.current.all { it.skippedDate == null })
        assertEquals("Up already?", posted().single().title())
        assertTrue(log.events.drop(logged).none { it is LogEvent.OperationFailed })
    }

    @Test
    fun `the receiver is not exported`() {
        val info = app.packageManager.getReceiverInfo(ComponentName(app, SkipReminderActionReceiver::class.java), 0)

        assertEquals(false, info.exported)
        assertNull(shadowOf(app).nextStartedActivity)
        app.sendBroadcast(Intent("com.example.SPOOF").setClass(app, SkipReminderActionReceiver::class.java))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(alarms.current.all { it.skippedDate == null })
    }

    private companion object {
        const val POLL_MILLIS = 20L
    }
}

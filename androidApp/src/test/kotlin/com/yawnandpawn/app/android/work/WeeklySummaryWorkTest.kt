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
import com.yawnandpawn.app.android.summary.AndroidWeeklySummaryNotifier
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.stats.IsoWeek
import com.yawnandpawn.app.core.stats.WeeklySummary
import com.yawnandpawn.app.core.summary.SetWeeklySummary
import com.yawnandpawn.app.core.summary.WeeklySummaryNotifier
import com.yawnandpawn.app.core.summary.WeeklySummaryScheduler
import com.yawnandpawn.app.core.summary.WeeklySummaryStore
import com.yawnandpawn.app.core.summary.WeeklySummaryTask
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.core.work.BackgroundTaskKind
import com.yawnandpawn.app.core.work.BackgroundWork
import com.yawnandpawn.app.core.work.WeeklySummaryJobs
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakePurchaseRecordRepository
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.FakeUserLockState
import com.yawnandpawn.app.testing.aPurchaseRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Story 6.8 (FR-PRG-5, AD-17): the weekly summary on WorkManager's own test setup (`WorkManagerTestInitHelper` and its
 * `TestDriver`), with the real adapter, scheduler, task and notifier over a pinned clock and zone. One unique
 * `weekly-summary` work waits for the next Sunday 19:00 local; its run posts the week's text once, chains next week's
 * run after itself (never cancelling itself), and posts nothing when late, a duplicate, empty or not permitted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class WeeklySummaryWorkTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val berlin = TimeZone.of("Europe/Berlin")
    private val log = FakeLogger()

    // Saturday 2027-03-06 19:00 in Berlin: the next target is Sunday 2027-03-07 19:00, 24 h later.
    private val clock = FakeClock(berlin("2027-03-06T19:00"))
    private val zones = FakeTimeZoneProvider(berlin)
    private val history = FakeSessionHistoryRepository()
    private val records = FakePurchaseRecordRepository()

    private fun berlin(text: String): Instant = LocalDateTime.parse(text).toInstant(berlin)

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
                single<TimeZoneProvider> { zones }
                single<SessionHistoryRepository> { history }
                single<PurchaseRecordRepository> { records }
            },
        )
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    // WorkManager's test instance is process-wide: closed here, so no later test of this JVM finds its database open.
    @After
    fun tearDown() = WorkManagerTestInitHelper.closeWorkDatabase()

    private val koin get() = GlobalContext.get()
    private val scheduler: WeeklySummaryScheduler get() = koin.get()
    private val workManager: WorkManager get() = WorkManager.getInstance(app)
    private val notifications get() = app.getSystemService(NotificationManager::class.java)

    private fun infos(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(WeeklySummaryJobs.NAME).getBounded()

    private fun <T> ListenableFuture<T>.getBounded(): T = get(APP_WORK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)

    private fun pending(): WorkInfo = assertNotNull(infos().singleOrNull { !it.state.isFinished }, "one pending run: ${infos()}")

    private fun posted(): List<Notification> = shadowOf(notifications).allNotifications

    /** A finished session of each local [days] ("2027-03-01") at 06:30 in Berlin, ending as [outcome]. */
    private fun sessions(
        vararg days: String,
        outcome: SessionOutcome = SessionOutcome.OnTime,
    ) = runBlocking {
        days.forEach { day ->
            val at = berlin("${day}T06:30")
            val row =
                SessionHistoryRow(
                    sessionId = "$day-$outcome",
                    alarmId = "a",
                    scheduledAt = at,
                    firstRingAt = at,
                    endedAt = at + 3.minutes,
                    snoozeCount = 0,
                    checkTypes = listOf("Math"),
                    timeToCompleteMs = 180_000,
                    fallbackUsed = false,
                    directBoot = false,
                    outcome = outcome,
                )
            assertEquals(Outcome.Success(Unit), history.upsert(row))
        }
    }

    /** Enqueues the work from Saturday 19:00 (24 h ahead), then runs it at [at] as WorkManager would once its delay passed. */
    private fun runAt(at: Instant): UUID {
        if (infos().none { !it.state.isFinished }) assertEquals(Outcome.Success(Unit), runBlocking { scheduler.start() })
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
    fun `the app start enqueues one unique work at the next Sunday 19 00, needing no network`() {
        assertEquals(Outcome.Success(Unit), runBlocking { scheduler.start() })

        val work = assertNotNull(infos().singleOrNull())
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertEquals(24.hours.inWholeMilliseconds, work.initialDelayMillis)
        assertEquals(NetworkType.NOT_REQUIRED, work.constraints.requiredNetworkType)
        assertTrue(AndroidBackgroundWork.TAG in work.tags)
        assertEquals(null, work.periodicityInfo)
        assertIs<WeeklySummaryTask>(koin.get<BackgroundTasks>().taskFor(BackgroundTaskKind.WeeklySummary))
    }

    @Test
    fun `on the Berlin spring DST weekend the delay is 30 h, not 31`() {
        clock.set(Instant.parse("2027-03-27T11:00:00Z"))

        runBlocking { scheduler.start() }

        assertEquals(30.hours.inWholeMilliseconds, pending().initialDelayMillis)
    }

    @Test
    fun `a run at Sunday 19 00 posts the week once, then chains next week's run after it, ending SUCCEEDED`() {
        sessions("2027-03-01", "2027-03-02", "2027-03-03", "2027-03-04", "2027-03-05")
        sessions("2027-03-06", outcome = SessionOutcome.Test)

        val run = runAt(berlin("2027-03-07T19:00"))

        assertEquals(WorkInfo.State.SUCCEEDED, workManager.getWorkInfoById(run).getBounded()?.state)
        val notification = posted().single()
        assertEquals("5 on-time mornings, nothing paid. Nice.", notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(IsoWeek(2027, 9), lastHandledWeek())
        awaitUntil("next week's run is enqueued") { infos().any { it.state == WorkInfo.State.ENQUEUED } }
        val next = pending()
        assertEquals(WorkInfo.State.ENQUEUED, next.state)
        assertEquals((berlin("2027-03-14T19:00") - clock.now()).inWholeMilliseconds, next.initialDelayMillis)
        assertEquals(1, infos().count { it.state == WorkInfo.State.SUCCEEDED })
    }

    @Test
    fun `the posted notification is on its own channel, titled with the app name, untinted, auto-cancelled, opening Progress`() {
        sessions("2027-03-01")

        runAt(berlin("2027-03-07T19:00"))

        val notification = posted().single()
        val channel = assertNotNull(notifications.getNotificationChannel(AndroidWeeklySummaryNotifier.CHANNEL_ID))
        assertEquals("weekly_summary", notification.channelId)
        assertEquals("Weekly summary", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals("Yawn & Pawn", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("1 on-time morning, nothing paid. Nice.", notification.extras.getString(Notification.EXTRA_TEXT))
        assertNotNull(notification.smallIcon)
        assertEquals(Notification.COLOR_DEFAULT, notification.color)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val tap = shadowOf(notification.contentIntent)
        assertTrue(tap.isActivityIntent)
        assertTrue(tap.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals(MainActivity::class.java.name, tap.savedIntent.component?.className)
        assertEquals(MainActivity.OPEN_PROGRESS, tap.savedIntent.getStringExtra(MainActivity.EXTRA_OPEN_TAB))
    }

    @Test
    fun `the other texts read as EXPERIENCE's Key strings, amounts per currency sorted by code (en-US)`() {
        val notifier = koin.get<WeeklySummaryNotifier>()
        val week = IsoWeek(2027, 9)
        val paid = listOf(Money(8_980_000, "EUR"), Money(1_000_000, "USD"))

        fun textOf(summary: WeeklySummary): String? {
            assertEquals(Outcome.Success(Unit), notifier.post(summary))
            return posted().last().extras.getString(Notification.EXTRA_TEXT)
        }

        assertEquals("0 on-time mornings this week. Next week's a fresh start.", textOf(WeeklySummary(week, 0, emptyList(), true)))
        assertEquals("3 on-time mornings, €8.98 + $1.00 paid this week.", textOf(WeeklySummary(week, 3, paid, true)))
        assertEquals("1 on-time morning, $1.00 paid this week.", textOf(WeeklySummary(week, 1, paid.drop(1), true)))
        assertEquals("0 on-time mornings, €8.98 paid this week.", textOf(WeeklySummary(week, 0, paid.take(1), true)))
    }

    @Test
    fun `a run sums this week's payments per currency through the real ledger records (money)`() {
        sessions("2027-03-01", "2027-03-02", "2027-03-03")
        runBlocking {
            listOf(
                aPurchaseRecord("t1", RecordStatus.Consumed, price = Money(2_990_000, "EUR"), purchasedAt = berlin("2027-03-02T06:35")),
                aPurchaseRecord("t2", RecordStatus.Consumed, price = Money(1_000_000, "USD"), purchasedAt = berlin("2027-03-03T06:35")),
                aPurchaseRecord("t3", RecordStatus.Consumed, price = Money(5_990_000, "EUR"), purchasedAt = berlin("2027-03-04T06:35")),
                // The week before, and a refunded payment: never counted.
                aPurchaseRecord("t4", RecordStatus.Consumed, price = Money(1_000_000, "USD"), purchasedAt = berlin("2027-02-28T06:35")),
                aPurchaseRecord("t5", RecordStatus.Stranded, price = Money(1_000_000, "USD"), purchasedAt = berlin("2027-03-04T06:40")),
            ).forEach { records.putRecord(it) }
        }

        runAt(berlin("2027-03-07T19:00"))

        assertEquals("3 on-time mornings, €8.98 + $1.00 paid this week.", posted().single().extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `a week with only test sessions posts nothing`() {
        sessions("2027-03-01", "2027-03-02", outcome = SessionOutcome.Test)

        runAt(berlin("2027-03-07T19:00"))

        assertEquals(emptyList(), posted())
    }

    @Test
    fun `a run on Tuesday, more than 24 h late, posts nothing`() {
        sessions("2027-03-01")

        runAt(berlin("2027-03-09T08:00"))

        assertEquals(emptyList(), posted())
        assertEquals(IsoWeek(2027, 9), lastHandledWeek())
        awaitUntil("next week's run is enqueued") { infos().any { it.state == WorkInfo.State.ENQUEUED } }
    }

    @Test
    fun `a second run for the same week posts nothing more`() {
        sessions("2027-03-01")
        runAt(berlin("2027-03-07T19:00"))
        awaitUntil("next week's run is enqueued") { infos().any { it.state == WorkInfo.State.ENQUEUED } }

        // WorkManager runs the chained work early, still inside the week's window: the week is handled already.
        runAt(berlin("2027-03-07T20:00"))

        assertEquals(1, posted().size)
    }

    @Test
    fun `without notifications nothing is posted and nothing asks for the permission`() {
        sessions("2027-03-01")
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        runAt(berlin("2027-03-07T19:00"))

        assertEquals(emptyList(), posted())
        assertNull(shadowOf(app).nextStartedActivity, "no activity starts to ask")

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(false)
        assertEquals(false, koin.get<WeeklySummaryNotifier>().canPost())
    }

    @Test
    fun `turning the switch off cancels the work at once, and on enqueues it again`() {
        // The switch's writes wait for the stored session to be restored (the session lock, Story 2.6).
        runBlocking { koin.get<SessionEngine>().restore() }
        runBlocking { scheduler.start() }
        assertEquals(1, pendingCount())

        assertEquals(Outcome.Success(Unit), runBlocking { koin.get<SetWeeklySummary>()(false) })
        awaitUntil("the work is cancelled") { pendingCount() == 0 }

        assertEquals(Outcome.Success(Unit), runBlocking { koin.get<SetWeeklySummary>()(true) })
        assertEquals(24.hours.inWholeMilliseconds, pending().initialDelayMillis)
    }

    @Test
    fun `a sync inside the due window keeps the pending run, and a time change outside it replaces it`() {
        runBlocking { scheduler.start() }
        val first = pending().id

        clock.set(berlin("2027-03-07T19:30"))
        runBlocking { koin.get<WeeklySummaryScheduler>().sync() }
        assertEquals(first, pending().id, "KEEP while a run is due")

        clock.set(berlin("2027-03-06T20:00"))
        runBlocking { koin.get<WeeklySummaryScheduler>().sync() }
        val replaced = pending()
        assertTrue(replaced.id != first, "REPLACE outside the due window")
        assertEquals(23.hours.inWholeMilliseconds, replaced.initialDelayMillis)
    }

    private fun pendingCount(): Int = infos().count { !it.state.isFinished }

    /** The last handled week the store holds now. */
    private fun lastHandledWeek(): IsoWeek? =
        runBlocking {
            koin
                .get<WeeklySummaryStore>()
                .observe()
                .first()
                .lastHandledWeek
        }

    private companion object {
        const val POLL_MILLIS = 20L
    }
}

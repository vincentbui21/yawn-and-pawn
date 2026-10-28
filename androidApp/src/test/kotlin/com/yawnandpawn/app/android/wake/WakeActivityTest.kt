package com.yawnandpawn.app.android.wake

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.buildActivity
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.Motivation
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionMergeRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.RuntimeWrite
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.launchActivity
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeTimeChangeSignal
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.testing.aSessionHistoryRow
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.format.formatLongDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toLocalDateTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Stories 1.14 and 1.15: the wake screen over the lock screen, rendering the approved Ringing screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WakeActivityTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun hiddenFlag(name: String): Int = ActivityInfo::class.java.getField(name).getInt(null)

    private fun zone(app: WakeApp) = app.koin.get<TimeZoneProvider>().current()

    /** How the screen writes the fixture's 06:00 UTC alarm in the phone's zone. */
    private fun alarmTime(
        app: WakeApp,
        is24Hour: Boolean = false,
        at: Instant = aSessionConfig().scheduledAt,
    ): String = formatClockTime(at.toLocalDateTime(zone(app)).time, is24Hour)

    private fun ringing(
        app: WakeApp,
        testMode: Boolean = false,
    ) {
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(testMode = testMode), beforeFirstUnlock = false))
        assertIs<SessionState.Ringing>(app.engine.state.value)
    }

    private fun launch(app: WakeApp) = launchActivity<WakeActivity>(Intent(app.app, WakeActivity::class.java))

    private fun imUp() = composeRule.onNodeWithText("I'm up")

    @Test
    fun `the manifest makes it direct-boot aware, over the lock screen, out of Recents, single-task in its own task and not exported`() {
        val app = WakeApp().app
        val info = app.packageManager.getActivityInfo(ComponentName(app, WakeActivity::class.java), 0)

        assertTrue(info.directBootAware, "directBootAware")
        assertFalse(info.exported, "exported")
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
        assertEquals("com.yawnandpawn.app.wake", info.taskAffinity)
        assertTrue(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0, "excludeFromRecents")
        // The two flags are hidden ActivityInfo constants.
        assertTrue(info.flags and hiddenFlag("FLAG_SHOW_WHEN_LOCKED") != 0, "showWhenLocked")
        assertTrue(info.flags and hiddenFlag("FLAG_TURN_SCREEN_ON") != 0, "turnScreenOn")
    }

    @Test
    fun `the manifest declares the wake service as a direct-boot media playback foreground service, not exported`() {
        val app = WakeApp().app
        val info = app.packageManager.getServiceInfo(ComponentName(app, WakeService::class.java), PackageManager.GET_META_DATA)

        assertTrue(info.directBootAware, "directBootAware")
        assertFalse(info.exported, "exported")
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, info.foregroundServiceType)
    }

    @Test
    fun `it shows over the lock screen, turns and keeps the screen on, and Back does nothing`() {
        val app = WakeApp()
        ringing(app)

        val activity = buildActivity(WakeActivity::class.java).setup().get()

        assertTrue(shadowOf(activity).showWhenLocked)
        assertTrue(shadowOf(activity).turnScreenOn)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        activity.onBackPressedDispatcher.onBackPressed()
        assertFalse(activity.isFinishing, "Back does nothing")
        assertIs<SessionState.Ringing>(app.engine.state.value)
    }

    @Test
    @Config(sdk = [26])
    fun `on API 26 it uses the window flags`() {
        val app = WakeApp()
        ringing(app)

        val activity = buildActivity(WakeActivity::class.java).setup().get()

        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        assertEquals(flags, activity.window.attributes.flags and flags)
    }

    @Test
    fun `a ringing session renders the Ringing screen with label, alarm time, date, a 72 dp I'm up and snooze waiting for prices`() {
        val app = WakeApp(clock = FakeClock(aSessionConfig().scheduledAt))
        ringing(app)

        launch(app)

        composeRule.onNodeWithText("Work").assertExists()
        composeRule.onNodeWithContentDescription(alarmTime(app)).assertExists()
        composeRule.onNodeWithText(formatLongDate(aSessionConfig().scheduledAt.toLocalDateTime(zone(app)).date)).assertExists()
        assertTrue(imUp().getBoundsInRoot().let { it.bottom - it.top } >= 72.dp, "the largest wake action")
        composeRule.onNodeWithContentDescription("Snooze unavailable, prices not loaded yet").assertExists()
    }

    @Test
    fun `a test session's snooze reads Test no charge`() {
        val app = WakeApp()
        ringing(app, testMode = true)

        launch(app)

        composeRule.onNodeWithContentDescription("Snooze unavailable, Test · no charge").assertExists()
    }

    @Test
    fun `with the 24-hour setting the clock shows the 24-hour time`() {
        val app = WakeApp(clock = FakeClock(aSessionConfig().scheduledAt))
        Settings.System.putString(app.app.contentResolver, Settings.System.TIME_12_24, "24")
        ringing(app)

        launch(app)

        composeRule.onNodeWithContentDescription(alarmTime(app, is24Hour = true)).assertExists()
    }

    @Test
    fun `no repository or session store call happens before the first frame`() {
        val alarms = CountingAlarmRepository()
        val store = CountingStore()
        val history = CountingHistory()
        val app = WakeApp(store = store, repository = alarms, history = history)
        ringing(app)
        app.koin.get<ApplicationScope>().awaitChildren()
        val before = listOf(alarms.calls.get(), store.calls.get(), history.calls.get())
        assertEquals(0, history.allCalls.get(), "no celebration read before the screen exists")

        launch(app)
        imUp().assertExists()

        // The celebration's one history read (Story 6.4) is counted on its own: it follows the first frame.
        assertEquals(before, listOf(alarms.calls.get(), store.calls.get(), history.calls.get()), "alarms, store, history calls")
    }

    /** Story 6.4 (NFR-7): the celebration's history read starts after the first frame, and the screen never waits for it. */
    @Test
    fun `the history read starts only after the ringing screen's first frame, once per session`() {
        val history = FakeSessionHistoryRepository()
        val gate = CompletableDeferred<Unit>()
        history.allGate = gate
        val app = WakeApp(history = history)
        ringing(app)
        app.koin.get<ApplicationScope>().awaitChildren()
        assertEquals(0, history.allCalls, "nothing read while the session starts")

        // The compose clock held: the screen is composed, but no frame has run yet.
        composeRule.mainClock.autoAdvance = false
        val controller = buildActivity(WakeActivity::class.java).setup()
        imUp().assertExists()
        repeat(IDLE_ROUNDS) { shadowOf(Looper.getMainLooper()).idle() }
        app.koin.get<ApplicationScope>().awaitChildren()
        assertEquals(0, history.allCalls, "no read before the first frames")

        composeRule.mainClock.advanceTimeBy(FRAMES)
        app.awaitUntil("the read starts after the first frame") { history.allCalls == 1 }
        composeRule.mainClock.autoAdvance = true
        // The read is still held: the screen is drawn and works without it.
        imUp().assertExists()
        assertFalse(gate.isCompleted)
        gate.complete(Unit)
        app.koin.get<ApplicationScope>().awaitChildren()

        controller.recreate()
        imUp().assertExists()
        composeRule.mainClock.advanceTimeBy(FRAMES)
        app.koin.get<ApplicationScope>().awaitChildren()
        assertEquals(1, history.allCalls, "one read per session, also across a recreation")
    }

    /** Story 6.4: a real zero-snooze morning after 11 good days shows "12", "days in a row" and "Up on time.". */
    @Test
    fun `a zero-snooze session after 11 good days shows the streak of 12`() {
        val scheduled = aSessionConfig().scheduledAt
        val history = FakeSessionHistoryRepository()
        runBlocking {
            (1..11).forEach { day ->
                history.upsert(aSessionHistoryRow(sessionId = "earlier-$day").copy(scheduledAt = scheduled - day.days))
            }
        }
        val app = WakeApp(history = history, clock = FakeClock(scheduled + 5.minutes))
        ringing(app)
        buildActivity(WakeActivity::class.java).setup()
        imUp().assertExists()
        app.awaitUntil("the snapshot is read") { composeRule.waitForIdle().let { history.allCalls == 1 } }
        app.koin.get<ApplicationScope>().awaitChildren()

        imUp().performClick()

        composeRule.awaitSuccess(app, "Up on time.")
        composeRule.onNodeWithContentDescription("12").assertExists()
        composeRule.onNodeWithText("days in a row").assertExists()
    }

    /** Story 6.4: with no snapshot (the read failed), Success shows "You're up. That's what counts." and logs it. */
    @Test
    fun `a failed history read shows You're up with no number and is logged`() {
        val history = FakeSessionHistoryRepository()
        history.failAll = DomainError.StorageFailure("disk gone")
        val app = WakeApp(history = history)
        ringing(app)
        buildActivity(WakeActivity::class.java).setup()
        imUp().assertExists()
        app.awaitUntil("the snapshot read fails") { composeRule.waitForIdle().let { history.allCalls == 1 } }
        app.koin.get<ApplicationScope>().awaitChildren()

        imUp().performClick()

        composeRule.awaitSuccess(app, "You're up. That's what counts.")
        composeRule.onNodeWithText("days in a row", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Up on time.").assertDoesNotExist()
        assertTrue(app.logs().any { it.startsWith("OperationFailed operation=load celebration streak ") }, "logged: ${app.logs()}")
    }

    @Test
    fun `a test session never reads history for the celebration`() {
        val history = FakeSessionHistoryRepository()
        val app = WakeApp(history = history)
        ringing(app, testMode = true)
        buildActivity(WakeActivity::class.java).setup()
        imUp().assertExists()
        composeRule.mainClock.advanceTimeBy(FRAMES)
        app.koin.get<ApplicationScope>().awaitChildren()

        assertEquals(0, history.allCalls)
    }

    @Test
    fun `I'm up ends the session on time, answering the placeholder, stopping sound and notification and showing Success`() {
        val history = FakeSessionHistoryRepository()
        val app = WakeApp(history = history)
        ringing(app)
        app.awaitRinging()
        val notifications = shadowOf(app.app.getSystemService(NotificationManager::class.java))
        assertEquals(1, notifications.size(), "the ringing notification")

        val activity = buildActivity(WakeActivity::class.java).setup().get()
        imUp().performClick()

        // The compose clock drives the recomposition that answers the placeholder step; Success follows (Story 3.3).
        composeRule.awaitSuccess(app, "Up on time.")
        // The engine publishes Idle before its end effects run: wait for them too.
        app.awaitUntil("the runtime stopped the ring") { app.player.sound == null && notifications.size() == 0 }
        assertNull(app.player.sound, "no sound")
        assertEquals(0, notifications.size(), "no notification")
        assertEquals(SessionOutcome.OnTime, history.rows.single().outcome)
        assertFalse(activity.isFinishing, "Success stays until Done")
    }

    @Test
    fun `a forgotten alarm stopped as Missed closes the wake screen`() {
        val app = WakeApp()
        ringing(app)
        val activity = buildActivity(WakeActivity::class.java).setup().get()
        composeRule.waitForIdle()

        // 30 minutes pass with no interaction, then the wake service's deadline tick runs (Story 1.16).
        ShadowSystemClock.advanceBy(Duration.ofMinutes(31))
        val tick = app.koin.get<ApplicationScope>().launch { app.engine.tick() }
        app.awaitUntil("the tick runs") { tick.isCompleted }

        composeRule.waitUntil(
            timeoutMillis = APP_WORK_TIMEOUT_MILLIS,
        ) { app.engine.state.value == SessionState.Idle && activity.isFinishing }
    }

    @Test
    fun `any other tap dispatches UserInteracted, which resets the interaction deadline`() {
        val app = WakeApp(clock = FakeClock(aSessionConfig().scheduledAt))
        ringing(app)
        val before = (app.engine.state.value as SessionState.Ringing).session.interactionDeadline
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))

        launch(app)
        composeRule.onNodeWithContentDescription(alarmTime(app)).performClick()

        app.awaitUntil("the deadline moves") {
            (app.engine.state.value as? SessionState.Ringing)?.session?.interactionDeadline != before
        }
        assertNotEquals(before, (app.engine.state.value as SessionState.Ringing).session.interactionDeadline)
    }

    @Test
    fun `a tap on the disabled snooze is an interaction, nothing more`() {
        val app = WakeApp()
        ringing(app)
        val before = (app.engine.state.value as SessionState.Ringing).session.interactionDeadline
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))

        launch(app)
        composeRule.onNodeWithContentDescription("Snooze unavailable, prices not loaded yet").performClick()

        app.awaitUntil("the deadline moves") {
            (app.engine.state.value as? SessionState.Ringing)?.session?.interactionDeadline != before
        }
        assertIs<SessionState.Ringing>(app.engine.state.value)
    }

    @Test
    fun `opened on a quiet placeholder step it answers it and the session ends with Success`() {
        val app = WakeApp()
        ringing(app)
        app.dispatch(SessionEvent.ImUpTapped)
        assertIs<SessionState.Grace>(app.engine.state.value)

        buildActivity(WakeActivity::class.java).setup().get()

        // The answer is sent from the screen's composition effect, which the compose rule runs: wait through it.
        composeRule.awaitScreen(app, "the session ends") { app.engine.state.value == SessionState.Idle }
        composeRule.awaitSuccess(app, "Up on time.")
    }

    @Test
    fun `a placeholder answer whose commit fails is sent again until it is stored`() {
        val store = CountingStore()
        val app = WakeApp(store = store)
        ringing(app)
        app.dispatch(SessionEvent.ImUpTapped)
        assertIs<SessionState.Grace>(app.engine.state.value)
        store.inner.commitFailure = DomainError.StorageFailure("disk full")
        val callsBefore = store.calls.get()

        buildActivity(WakeActivity::class.java).setup().get()
        composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) { store.calls.get() > callsBefore }
        assertIs<SessionState.Grace>(app.engine.state.value, "the failed answer changed nothing")
        store.inner.commitFailure = null

        composeRule.awaitSuccess(app, "Up on time.")
        assertTrue(store.calls.get() > callsBefore + 1, "answered again after the failure")
    }

    @Test
    fun `I'm up tapped before the session starts is kept and ends the session once it rings`() {
        val app = WakeApp()
        val at = aSessionConfig().scheduledAt
        app.koin.get<WakeNotifier>().show(at)
        val activity = buildActivity(WakeActivity::class.java).setup().get()

        imUp().performClick()
        composeRule.waitForIdle()
        assertEquals(SessionState.Idle, app.engine.state.value, "no session yet")
        assertFalse(activity.isFinishing, "it waits for the session")
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(), beforeFirstUnlock = false))

        composeRule.awaitSuccess(app, "Up on time.")
    }

    @Test
    fun `opened on a restored loud placeholder step it answers it and the session ends with Success`() {
        val loud = SessionState.Loud(aSession(sessionId = "session-loud"))
        val app = WakeApp(store = FakeActiveSessionStore(loud))
        val restore = app.koin.get<ApplicationScope>().launch { app.engine.restore() }
        app.awaitUntil("the session is restored") { restore.isCompleted }
        assertIs<SessionState.Loud>(app.engine.state.value)

        buildActivity(WakeActivity::class.java).setup().get()

        // The answer is sent from the screen's composition effect, which the compose rule runs: wait through it.
        composeRule.awaitScreen(app, "the session ends") { app.engine.state.value == SessionState.Idle }
        composeRule.awaitSuccess(app, "Up on time.")
    }

    @Test
    fun `in the emergency ring it shows the time now, I'm up stops it and the screen closes`() {
        val at = Instant.parse("2027-03-03T06:15:00Z")
        val app = WakeApp(clock = FakeClock(at))
        app.runtime.startEmergency(at, volumePercent = 80, cause = "commit failed")

        val activity = buildActivity(WakeActivity::class.java).setup().get()
        composeRule.onNodeWithContentDescription(alarmTime(app, at = at)).assertExists()
        composeRule.onNodeWithContentDescription("Snooze unavailable, prices not loaded yet").assertExists()
        imUp().performClick()
        composeRule.waitForIdle()

        assertNull(app.runtime.emergency.value)
        assertNull(app.player.sound)
        composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) { activity.isFinishing }
    }

    @Test
    fun `opened before the session starts it shows the time now and waits`() {
        val at = Instant.parse("2027-03-03T06:30:00Z")
        val app = WakeApp(clock = FakeClock(at))
        app.koin.get<WakeNotifier>().show(at)

        val activity = buildActivity(WakeActivity::class.java).setup().get()

        composeRule.onNodeWithContentDescription(alarmTime(app, at = at)).assertExists()
        assertFalse(activity.isFinishing, "still Idle: it waits for the session")
    }

    /**
     * Epic 4 device follow-up 1 (owner-approved): a ring 9 minutes after the alarm (a re-ring after a snooze, here a late
     * first ring) shows the time it rings, not the alarm's own time, which the notification keeps.
     */
    @Test
    fun `the Ringing clock shows the time now, not the alarm's scheduled time`() {
        val scheduled = aSessionConfig().scheduledAt
        val app = WakeApp(clock = FakeClock(scheduled + 9.minutes))
        ringing(app)

        buildActivity(WakeActivity::class.java).setup()

        composeRule.onNodeWithContentDescription(alarmTime(app, at = scheduled + 9.minutes)).assertExists()
        composeRule.onNodeWithContentDescription(alarmTime(app, at = scheduled)).assertDoesNotExist()
    }

    /** Review fix 1: the clock follows the app's time-change signal (the system's minute tick, a clock set, a zone change). */
    @Test
    fun `the Ringing clock moves on at a time-change signal`() {
        val scheduled = aSessionConfig().scheduledAt
        val clock = FakeClock(scheduled)
        val changes = FakeTimeChangeSignal()
        val app = WakeApp(clock = clock, timeChanges = changes)
        ringing(app)
        buildActivity(WakeActivity::class.java).setup()
        composeRule.onNodeWithContentDescription(alarmTime(app, at = scheduled)).assertExists()

        clock.set(scheduled + 3.minutes)
        changes.emit()

        composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithContentDescription(alarmTime(app, at = scheduled + 3.minutes)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(alarmTime(app, at = scheduled)).assertDoesNotExist()
    }

    /** Review fix 6: with no signal at all, the clock still turns over at the minute boundary. */
    @Test
    fun `the Ringing clock turns over at the minute without a signal`() {
        val clock = FakeClock(Instant.parse("2027-03-03T06:09:40Z"))
        val app = WakeApp(clock = clock, timeChanges = FakeTimeChangeSignal())
        ringing(app)
        buildActivity(WakeActivity::class.java).setup()
        composeRule.onNodeWithContentDescription(alarmTime(app, at = Instant.parse("2027-03-03T06:09:40Z"))).assertExists()

        clock.set(Instant.parse("2027-03-03T06:10:00Z"))
        ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(20))
        composeRule.mainClock.advanceTimeBy(20_000)
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
            val shown = alarmTime(app, at = Instant.parse("2027-03-03T06:10:00Z"))
            composeRule.onAllNodesWithContentDescription(shown).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `opened by the full-screen intent before the session starts it waits, shows the session and Success when it ends`() {
        val app = WakeApp(clock = FakeClock(aSessionConfig().scheduledAt))

        val activity = buildActivity(WakeActivity::class.java).setup().get()
        composeRule.waitForIdle()
        assertFalse(activity.isFinishing, "still Idle: it waits for the session")
        ringing(app)
        composeRule.onNodeWithContentDescription(alarmTime(app)).assertExists()

        // The screen answers the placeholder step itself.
        app.dispatch(SessionEvent.ImUpTapped)
        composeRule.awaitSuccess(app, "Up on time.")
    }
}

/** An [AlarmRepository] that counts every call. */
private class CountingAlarmRepository(
    private val inner: FakeAlarmRepository = FakeAlarmRepository(),
) : AlarmRepository {
    val calls = AtomicInteger()

    override fun observeAll(): Flow<List<Alarm>> = inner.observeAll().also { calls.incrementAndGet() }

    override suspend fun listAll(): Outcome<List<Alarm>, DomainError> = inner.listAll().also { calls.incrementAndGet() }

    override suspend fun get(id: String): Outcome<Alarm, DomainError> = inner.get(id).also { calls.incrementAndGet() }

    override suspend fun upsert(alarm: Alarm): Outcome<Unit, DomainError> = inner.upsert(alarm).also { calls.incrementAndGet() }

    override suspend fun delete(id: String): Outcome<Unit, DomainError> = inner.delete(id).also { calls.incrementAndGet() }

    override suspend fun clearMotivation(motivation: Motivation): Outcome<Unit, DomainError> =
        inner.clearMotivation(motivation).also { calls.incrementAndGet() }
}

/** An [ActiveSessionStore] that counts every call. */
private class CountingStore(
    val inner: FakeActiveSessionStore = FakeActiveSessionStore(),
) : ActiveSessionStore {
    val calls = AtomicInteger()

    override suspend fun load(): Outcome<StoredSession, DomainError> = inner.load().also { calls.incrementAndGet() }

    override suspend fun commit(
        state: SessionState,
        writes: List<RuntimeWrite>,
    ): Outcome<Unit, DomainError> = inner.commit(state, writes).also { calls.incrementAndGet() }

    override suspend fun clear(): Outcome<Unit, DomainError> = inner.clear().also { calls.incrementAndGet() }
}

/** A [SessionHistoryRepository] that counts every call. */
private class CountingHistory(
    private val inner: FakeSessionHistoryRepository = FakeSessionHistoryRepository(),
) : SessionHistoryRepository {
    val calls = AtomicInteger()

    override suspend fun upsert(row: SessionHistoryRow): Outcome<Unit, DomainError> = inner.upsert(row).also { calls.incrementAndGet() }

    override suspend fun find(sessionId: String): Outcome<SessionHistoryRow?, DomainError> =
        inner.find(sessionId).also { calls.incrementAndGet() }

    override fun observeLatestMissed(): Flow<SessionHistoryRow?> = inner.observeLatestMissed().also { calls.incrementAndGet() }

    override fun observeAll(): Flow<List<SessionHistoryRow>> = inner.observeAll().also { calls.incrementAndGet() }

    override suspend fun recordMerge(merge: SessionMergeRow): Outcome<Unit, DomainError> =
        inner.recordMerge(merge).also { calls.incrementAndGet() }

    override suspend fun merges(sessionId: String): Outcome<List<SessionMergeRow>, DomainError> =
        inner.merges(sessionId).also { calls.incrementAndGet() }

    /** Story 6.4's celebration read, counted on its own: it is allowed, but only after the first frame. */
    val allCalls = AtomicInteger()

    override suspend fun all(): Outcome<List<SessionHistoryRow>, DomainError> {
        allCalls.incrementAndGet()
        return inner.all()
    }
}

/** Enough compose time for the first frames and the effects after them. */
private const val FRAMES = 100L

/** Main looper rounds that let anything the screen posts run. */
private const val IDLE_ROUNDS = 5

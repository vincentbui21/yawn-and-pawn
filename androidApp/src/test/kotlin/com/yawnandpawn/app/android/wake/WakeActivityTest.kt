package com.yawnandpawn.app.android.wake

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.format.formatLongDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(testMode = testMode), listOf(1L), beforeFirstUnlock = false))
        assertIs<SessionState.Ringing>(app.engine.state.value)
    }

    private fun launch(app: WakeApp) = ActivityScenario.launch<WakeActivity>(Intent(app.app, WakeActivity::class.java))

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

        val activity = Robolectric.buildActivity(WakeActivity::class.java).setup().get()

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

        val activity = Robolectric.buildActivity(WakeActivity::class.java).setup().get()

        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        assertEquals(flags, activity.window.attributes.flags and flags)
    }

    @Test
    fun `a ringing session renders the Ringing screen with label, alarm time, date, a 72 dp I'm up and snooze waiting for prices`() {
        val app = WakeApp()
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
        val app = WakeApp()
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

        launch(app)
        imUp().assertExists()

        assertEquals(before, listOf(alarms.calls.get(), store.calls.get(), history.calls.get()), "alarms, store, history calls")
    }

    @Test
    fun `I'm up ends the session on time, answering the placeholder, stopping sound and notification and closing the screen`() {
        val history = FakeSessionHistoryRepository()
        val app = WakeApp(history = history)
        ringing(app)
        app.awaitRinging()
        val notifications = shadowOf(app.app.getSystemService(NotificationManager::class.java))
        assertEquals(1, notifications.size(), "the ringing notification")

        val activity = Robolectric.buildActivity(WakeActivity::class.java).setup().get()
        imUp().performClick()

        // The compose clock drives the recomposition that answers the placeholder step.
        composeRule.waitUntil(timeoutMillis = 10_000) { app.engine.state.value == SessionState.Idle && activity.isFinishing }
        // The engine publishes Idle before its end effects run: wait for them too.
        app.awaitUntil("the runtime stopped the ring") { app.player.sound == null && notifications.size() == 0 }
        assertNull(app.player.sound, "no sound")
        assertEquals(0, notifications.size(), "no notification")
        assertEquals(SessionOutcome.OnTime, history.rows.single().outcome)
    }

    @Test
    fun `any other tap dispatches UserInteracted, which resets the interaction deadline`() {
        val app = WakeApp()
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
    fun `opened on a quiet placeholder step it answers it and the session ends`() {
        val app = WakeApp()
        ringing(app)
        app.dispatch(SessionEvent.ImUpTapped)
        assertIs<SessionState.Grace>(app.engine.state.value)

        val activity = Robolectric.buildActivity(WakeActivity::class.java).setup().get()

        app.awaitUntil("the session ends") { app.engine.state.value == SessionState.Idle }
        composeRule.waitUntil(timeoutMillis = 5_000) { activity.isFinishing }
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

        val activity = Robolectric.buildActivity(WakeActivity::class.java).setup().get()
        composeRule.waitUntil(timeoutMillis = 10_000) { store.calls.get() > callsBefore }
        assertIs<SessionState.Grace>(app.engine.state.value, "the failed answer changed nothing")
        store.inner.commitFailure = null

        composeRule.waitUntil(timeoutMillis = 10_000) { app.engine.state.value == SessionState.Idle && activity.isFinishing }
        assertTrue(store.calls.get() > callsBefore + 1, "answered again after the failure")
    }

    @Test
    fun `I'm up tapped before the session starts is kept and ends the session once it rings`() {
        val app = WakeApp()
        val at = aSessionConfig().scheduledAt
        app.koin.get<WakeNotifier>().show(at)
        val activity = Robolectric.buildActivity(WakeActivity::class.java).setup().get()

        imUp().performClick()
        composeRule.waitForIdle()
        assertEquals(SessionState.Idle, app.engine.state.value, "no session yet")
        assertFalse(activity.isFinishing, "it waits for the session")
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(), listOf(1L), beforeFirstUnlock = false))

        composeRule.waitUntil(timeoutMillis = 10_000) { app.engine.state.value == SessionState.Idle && activity.isFinishing }
    }

    @Test
    fun `opened on a restored loud placeholder step it answers it and the session ends`() {
        val loud = SessionState.Loud(aSession(sessionId = "session-loud"))
        val app = WakeApp(store = FakeActiveSessionStore(loud))
        val restore = app.koin.get<ApplicationScope>().launch { app.engine.restore() }
        app.awaitUntil("the session is restored") { restore.isCompleted }
        assertIs<SessionState.Loud>(app.engine.state.value)

        val activity = Robolectric.buildActivity(WakeActivity::class.java).setup().get()

        app.awaitUntil("the session ends") { app.engine.state.value == SessionState.Idle }
        composeRule.waitUntil(timeoutMillis = 5_000) { activity.isFinishing }
    }

    @Test
    fun `in the emergency ring it shows the alarm time, I'm up stops it and the screen closes`() {
        val app = WakeApp()
        val at = Instant.parse("2027-03-03T06:15:00Z")
        app.runtime.startEmergency(at, volumePercent = 80, cause = "commit failed")

        val activity = Robolectric.buildActivity(WakeActivity::class.java).setup().get()
        composeRule.onNodeWithContentDescription(alarmTime(app, at = at)).assertExists()
        composeRule.onNodeWithContentDescription("Snooze unavailable, prices not loaded yet").assertExists()
        imUp().performClick()
        composeRule.waitForIdle()

        assertNull(app.runtime.emergency.value)
        assertNull(app.player.sound)
        composeRule.waitUntil(timeoutMillis = 5_000) { activity.isFinishing }
    }

    @Test
    fun `opened before the session starts it shows the notification's alarm time and waits`() {
        val app = WakeApp()
        val at = Instant.parse("2027-03-03T06:30:00Z")
        app.koin.get<WakeNotifier>().show(at)

        val activity = Robolectric.buildActivity(WakeActivity::class.java).setup().get()

        composeRule.onNodeWithContentDescription(alarmTime(app, at = at)).assertExists()
        assertFalse(activity.isFinishing, "still Idle: it waits for the session")
    }

    @Test
    fun `opened by the full-screen intent before the session starts it waits, shows the session and closes when it ends`() {
        val app = WakeApp()

        val activity = Robolectric.buildActivity(WakeActivity::class.java).setup().get()
        composeRule.waitForIdle()
        assertFalse(activity.isFinishing, "still Idle: it waits for the session")
        ringing(app)
        composeRule.onNodeWithContentDescription(alarmTime(app)).assertExists()

        // The screen answers the placeholder step itself.
        app.dispatch(SessionEvent.ImUpTapped)
        composeRule.waitUntil(timeoutMillis = 5_000) { activity.isFinishing }
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
}

/** An [ActiveSessionStore] that counts every call. */
private class CountingStore(
    val inner: FakeActiveSessionStore = FakeActiveSessionStore(),
) : ActiveSessionStore {
    val calls = AtomicInteger()

    override suspend fun load(): Outcome<StoredSession, DomainError> = inner.load().also { calls.incrementAndGet() }

    override suspend fun commit(state: SessionState): Outcome<Unit, DomainError> = inner.commit(state).also { calls.incrementAndGet() }

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
}

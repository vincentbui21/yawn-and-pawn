package com.yawnandpawn.app.android.wake

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.buildActivity
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.SnoozeProducts
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.config.GlobalSettingsRepository
import com.yawnandpawn.app.core.config.SettingsSnapshot
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.PurchaseVerdict
import com.yawnandpawn.app.core.session.SessionConfig
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.SnoozeAvailabilityPolicy
import com.yawnandpawn.app.core.session.SnoozeOffer
import com.yawnandpawn.app.launchActivity
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.FakeBilling
import com.yawnandpawn.app.testing.FakeBillingCountry
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeDisplayPrices
import com.yawnandpawn.app.testing.FakeGlobalSettingsRepository
import com.yawnandpawn.app.testing.FakeLivePriceSource
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.aSessionConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 5.2 "Bright wake screen" (option C, "Ringing only"): the wake window is at full brightness (`1f`) only while a
 * session's Ringing screen shows (its snooze confirm sheet too) with the setting frozen on, the screen is started and
 * Extra dim is off; everywhere else, and after every exit, it is at the phone's own level (`-1f`). With the setting off
 * the window attribute is never written. The frozen config comes from the fire (the store) or, for a test ring, the
 * last-known settings snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WakeBrightnessTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    /** Snooze N + 1 on sale, priced by the fake display and live prices ($1 × N). */
    private val policy =
        SnoozeAvailabilityPolicy { session ->
            val number = session.snoozesGranted + 1
            SnoozeAvailability.Available(SnoozeOffer(SnoozeProducts.idOf(number), number))
        }
    private val store = FakeActiveSessionStore()

    private fun app(globalSettings: GlobalSettingsRepository? = null) =
        WakeApp(
            store = store,
            policy = policy,
            livePrices = FakeLivePriceSource(),
            displayPrices = FakeDisplayPrices(),
            billingCountry = FakeBillingCountry(),
            clock = FakeClock(),
            monotonic = FakeMonotonicClock(elapsedMillis = 1_000_000),
            billing = FakeBilling(),
            purchaseIntents = store.intents,
            globalSettings = globalSettings,
        )

    private fun ring(
        app: WakeApp,
        config: SessionConfig = aSessionConfig(),
    ) {
        app.dispatch(SessionEvent.AlarmFired("session-1", config, beforeFirstUnlock = false))
        assertIs<SessionState.Ringing>(app.engine.state.value)
    }

    private fun launch(app: WakeApp): ActivityScenario<WakeActivity> = launchActivity(Intent(app.app, WakeActivity::class.java))

    /** The wake screen of [scenario] once the composition has caught up with the engine. */
    private fun screen(
        app: WakeApp,
        scenario: ActivityScenario<WakeActivity>,
        what: String = "the screen",
        condition: () -> Boolean = { true },
    ): WakeActivity {
        composeRule.awaitScreen(app, what, condition)
        lateinit var screen: WakeActivity
        scenario.onActivity { screen = it }
        return screen
    }

    private fun WakeActivity.brightness(): Float = window.attributes.screenBrightness

    private fun shown(text: String): Boolean =
        composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    @Test
    fun `the Ringing screen with the setting on is at full brightness`() {
        val app = app()
        ring(app)
        launch(app).use { scenario ->
            assertEquals(FULL, screen(app, scenario) { shown("I'm up") }.brightness())
        }
    }

    @Test
    fun `with the setting off the Ringing screen stays at the phone's level`() {
        val app = app()
        ring(app, aSessionConfig().copy(brightWakeScreen = false))
        launch(app).use { scenario ->
            assertEquals(NONE, screen(app, scenario) { shown("I'm up") }.brightness())
        }
    }

    @Test
    fun `the snooze confirm sheet over the Ringing screen stays at full brightness`() {
        val app = app()
        ring(app)
        launch(app).use { scenario ->
            screen(app, scenario) { shown("Snooze · $1.00") }
            composeRule.onNodeWithText("Snooze · $1.00").performClick()
            val screen = screen(app, scenario, "the sheet opens") { shown("I'll get up") }

            assertEquals(FULL, screen.brightness())
        }
    }

    @Test
    fun `I'm up goes to the check at the phone's level`() {
        val app = app()
        ring(app, aSessionConfig().copy(checkPlan = CheckPlan.default()))
        launch(app).use { scenario ->
            assertEquals(FULL, screen(app, scenario) { shown("I'm up") }.brightness())

            composeRule.onNodeWithText("I'm up").performClick()
            val screen = screen(app, scenario, "the check") { app.engine.state.value is SessionState.Grace && !shown("I'm up") }

            assertEquals(NONE, screen.brightness())
        }
    }

    @Test
    fun `Success is at the phone's level`() {
        val app = app()
        ring(app)
        launch(app).use { scenario ->
            assertEquals(FULL, screen(app, scenario) { shown("I'm up") }.brightness())

            composeRule.onNodeWithText("I'm up").performClick()
            composeRule.awaitSuccess(app, "Up on time.")

            assertEquals(NONE, screen(app, scenario).brightness())
        }
    }

    @Test
    fun `the screen finishing while bright goes back to the phone's level`() {
        val app = app()
        ring(app)
        launch(app).use { scenario ->
            val screen = screen(app, scenario) { shown("I'm up") }
            assertEquals(FULL, screen.brightness())

            // Finishes it and runs its onStop and onDestroy, with the session still ringing (nothing else changes the level).
            scenario.moveToState(Lifecycle.State.DESTROYED)

            assertTrue(screen.isFinishing)
            assertIs<SessionState.Ringing>(app.engine.state.value)
            assertEquals(NONE, screen.brightness())
        }
    }

    @Test
    fun `I'm up tapped before the session exists never turns the screen bright`() {
        val app = WakeApp()
        app.koin.get<WakeNotifier>().show(aSessionConfig().scheduledAt)
        val screen = buildActivity(WakeActivity::class.java).setup().get()
        val levels = brightnessWrites(screen)

        composeRule.onNodeWithText("I'm up").performClick()
        composeRule.waitForIdle()
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(), beforeFirstUnlock = false))
        composeRule.awaitSuccess(app, "Up on time.")

        assertFalse(FULL in levels, "never full: $levels")
        assertEquals(NONE, screen.brightness())
    }

    @Test
    fun `back on a session that moved on while away, the screen never flashes bright`() {
        val app = app()
        ring(app, aSessionConfig().copy(checkPlan = CheckPlan.default()))
        launch(app).use { scenario ->
            val screen = screen(app, scenario) { shown("I'm up") }
            assertEquals(FULL, screen.brightness())

            scenario.moveToState(Lifecycle.State.CREATED) // away: Home, or the screen off
            val levels = brightnessWrites(screen)
            app.dispatch(SessionEvent.UserInteracted, SessionEvent.ImUpTapped)
            assertIs<SessionState.Grace>(app.engine.state.value)
            scenario.moveToState(Lifecycle.State.RESUMED)
            screen(app, scenario, "the check") { !shown("I'm up") }

            assertFalse(FULL in levels, "never full again: $levels")
            assertEquals(NONE, screen.brightness())
        }
    }

    @Test
    fun `the Snoozed screen is at the phone's level`() {
        val app = app()
        ring(app)
        launch(app).use { scenario ->
            assertEquals(FULL, screen(app, scenario) { shown("I'm up") }.brightness())

            app.dispatch(
                SessionEvent.PurchaseGranted(
                    SnoozeProducts.idOf(1),
                    PurchaseToken("token-1"),
                    PurchaseVerdict.Grant,
                    price = Money.of(1, "USD"),
                ),
            )
            val screen = screen(app, scenario, "Snoozed") { app.engine.state.value is SessionState.Snoozed && shown("Snoozed.") }

            assertEquals(NONE, screen.brightness())
        }
    }

    @Test
    fun `Home on the Ringing screen goes back to the phone's level, and coming back is full again`() {
        val app = app()
        ring(app)
        launch(app).use { scenario ->
            val screen = screen(app, scenario) { shown("I'm up") }
            assertEquals(FULL, screen.brightness())

            scenario.moveToState(Lifecycle.State.CREATED) // onStop: Home, Recents or the screen off
            assertEquals(NONE, screen.brightness(), "left")

            scenario.moveToState(Lifecycle.State.RESUMED) // onStart again
            assertEquals(FULL, screen(app, scenario).brightness(), "back")
        }
    }

    @Test
    fun `with Extra dim on the Ringing screen stays at the phone's level`() {
        val app = app()
        Settings.Secure.putInt(app.app.contentResolver, WakeBrightness.EXTRA_DIM_KEY, 1)
        ring(app)
        launch(app).use { scenario ->
            assertEquals(NONE, screen(app, scenario) { shown("I'm up") }.brightness())
        }
    }

    @Test
    fun `the emergency ring's Ringing screen stays at the phone's level`() {
        val app = app()
        app.runtime.startEmergency(RINGS_AT, volumePercent = 80, cause = "test")
        launch(app).use { scenario ->
            assertEquals(NONE, screen(app, scenario) { shown("I'm up") }.brightness())
        }
    }

    @Test
    fun `a ring that ends Missed while at full brightness closes the screen at the phone's level`() {
        // The real clocks, so the system clock can run past the 30 minutes.
        val app = WakeApp()
        ring(app)
        launch(app).use { scenario ->
            val screen = screen(app, scenario) { shown("I'm up") }
            assertEquals(FULL, screen.brightness())

            ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(31))
            val tick = app.koin.get<ApplicationScope>().launch { app.engine.tick() }
            app.awaitUntil("the tick runs") { tick.isCompleted }
            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) { screen.isFinishing }
            composeRule.waitForIdle()

            assertEquals(NONE, screen.brightness())
        }
    }

    @Test
    fun `before the session shows the screen stays at the phone's level`() {
        val app = app()
        launch(app).use { scenario ->
            assertEquals(NONE, screen(app, scenario).brightness())
        }
    }

    @Test
    fun `the fire freezes the stored setting, and a change after the fire leaves the ring as it was`() {
        val app = WakeApp()
        val settings = app.koin.get<GlobalSettingsRepository>()
        val alarm = savedAlarm(app)
        runBlocking { settings.setBrightWakeScreen(false) }

        app.ring(AlarmFired(alarm.id, RINGS_AT))
        app.awaitRinging()
        assertFalse(ringingConfig(app).brightWakeScreen, "frozen off at the fire")
        launch(app).use { scenario ->
            assertEquals(NONE, screen(app, scenario) { shown("I'm up") }.brightness())

            // Turned on mid-session (straight in the store; the use case refuses during a session): no effect.
            runBlocking { settings.setBrightWakeScreen(true) }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(NONE, screen(app, scenario).brightness())
            assertFalse(ringingConfig(app).brightWakeScreen)
        }
    }

    @Test
    fun `a test ring takes the setting from the last-known snapshot at its fire`() {
        val lastKnown = SettingsSnapshot(GlobalSettings(brightWakeScreen = false), emptyList())

        assertFalse(testRingConfig(lastKnown, stored = true).brightWakeScreen)
    }

    @Test
    fun `a test ring with no last-known snapshot keeps the value stored when Test alarm was tapped`() {
        assertTrue(testRingConfig(lastKnown = null, stored = true).brightWakeScreen)
    }

    /** The config a test ring rings with, its pending config holding [stored], the settings cache [lastKnown]. */
    private fun testRingConfig(
        lastKnown: SettingsSnapshot?,
        stored: Boolean,
    ): SessionConfig {
        val testAlarms = FakeTestAlarmStore(pending = aSessionConfig(testMode = true).copy(brightWakeScreen = stored))
        val settings = FakeGlobalSettingsRepository().apply { lastKnownSnapshot = lastKnown }
        val app = WakeApp(testAlarms = testAlarms, globalSettings = settings)

        app.startService(WakeService.intent(app.app, WakeService.ACTION_TEST))
        app.awaitRinging()

        return ringingConfig(app)
    }

    @Test
    fun `the helper writes the window only when the level changes, and not while full is never wanted`() {
        val window = buildActivity(Activity::class.java).setup().get().window
        var writes = 0
        var dim = false
        var dimReads = 0
        val brightness = WakeBrightness(window = { window.also { writes++ } }, extraDim = { dimReads++.let { dim } })

        brightness.onStart()
        brightness.show(full = false)
        brightness.show(full = false)
        assertEquals(0, writes, "not wanted: never written")
        assertEquals(0, dimReads, "Extra dim is not read while full cannot apply")
        assertEquals(NONE, window.attributes.screenBrightness)

        brightness.show(full = true)
        brightness.show(full = true)
        assertEquals(FULL, window.attributes.screenBrightness)
        assertEquals(1, writes)
        assertEquals(1, dimReads, "read once when full first applies")

        brightness.onStop()
        assertEquals(NONE, window.attributes.screenBrightness)
        brightness.onStart()
        assertEquals(FULL, window.attributes.screenBrightness)

        brightness.show(full = false)
        assertEquals(NONE, window.attributes.screenBrightness)

        // Extra dim is read at most once per start: turned on, it counts from the next start.
        dim = true
        brightness.onStop()
        brightness.onStart()
        brightness.show(full = true)
        assertEquals(NONE, window.attributes.screenBrightness, "Extra dim on")
        assertEquals(4, writes)
        assertEquals(3, dimReads, "at most one read per start")
    }

    @Test
    fun `a screen no longer wanted while stopped is not written full on the next start`() {
        val window = buildActivity(Activity::class.java).setup().get().window
        val brightness = WakeBrightness(window = { window }, extraDim = { false })
        brightness.onStart()
        brightness.show(full = true)
        brightness.onStop()
        val levels = brightnessWrites(window)

        brightness.show(full = false)
        brightness.onStart()

        assertFalse(FULL in levels, "no full write: $levels")
        assertEquals(NONE, window.attributes.screenBrightness)
    }

    @Test
    fun `Extra dim reads as on when set, and off when not set`() {
        val app = WakeApp().app
        assertFalse(WakeBrightness.extraDimOn(app.contentResolver))

        Settings.Secure.putInt(app.contentResolver, WakeBrightness.EXTRA_DIM_KEY, 1)

        assertTrue(WakeBrightness.extraDimOn(app.contentResolver))
    }

    @Test
    fun `an Extra dim read that throws counts as on and reports its cause`() {
        val failures = mutableListOf<String>()

        val dim = WakeBrightness.extraDimOn(Build.VERSION_CODES.S, failures::add) { throw SecurityException("hidden setting") }

        assertTrue(dim)
        assertEquals(listOf("SecurityException"), failures)
    }

    @Test
    fun `before API 31 there is no Extra dim, and nothing is read`() {
        var reads = 0

        val dim = WakeBrightness.extraDimOn(Build.VERSION_CODES.R, onFailure = {}) { reads++.let { 1 } }

        assertFalse(dim)
        assertEquals(0, reads)
    }

    /** Every brightness [activity]'s window is set to from now on. */
    private fun brightnessWrites(activity: Activity): List<Float> = brightnessWrites(activity.window)

    /** Every brightness [window] is set to from now on (each attribute change reaches its callback). */
    private fun brightnessWrites(window: Window): List<Float> {
        val levels = mutableListOf<Float>()
        val original = window.callback
        window.callback =
            object : Window.Callback by original {
                override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) {
                    levels += attrs.screenBrightness
                    original.onWindowAttributesChanged(attrs)
                }
            }
        return levels
    }

    private fun savedAlarm(app: WakeApp): Alarm {
        runBlocking { app.engine.restore() }
        val saved = runBlocking { app.koin.get<SaveAlarm>()(AlarmDraft(time = LocalTime(6, 0))) }
        return assertIs<Outcome.Success<Alarm>>(saved).value
    }

    private fun ringingConfig(app: WakeApp): SessionConfig = assertIs<SessionState.Ringing>(app.engine.state.value).session.config

    private companion object {
        const val FULL = 1f
        const val NONE = -1f

        // After the real clock: nothing waiting for it is due.
        val RINGS_AT: Instant = Instant.parse("2027-03-08T06:00:00Z")
    }
}

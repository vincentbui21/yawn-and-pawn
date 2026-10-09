package com.yawnandpawn.app.android.work

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.wake.WakeActivity
import com.yawnandpawn.app.android.wake.WakeApp
import com.yawnandpawn.app.android.wake.WakeService
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.billing.PriceCacheStore
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.billing.SnoozeProducts
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.launchActivity
import com.yawnandpawn.app.testing.FakeProductDetailsSource
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.FakeUserLockState
import com.yawnandpawn.app.testing.aPriceSnapshot
import com.yawnandpawn.app.testing.aSessionConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 4.3 (PRD §6.2): a real alarm that starts a session while the user is unlocked sets off one price refresh, and
 * nothing waits for it: the ring starts and the wake screen draws its first frame while Play is still answering, and
 * the cached prices are readable at once. Before the first unlock no refresh starts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PriceRefreshAtSessionStartTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val scheduledAt = Instant.parse("2027-03-08T06:00:00Z")

    private fun save(
        app: WakeApp,
        time: LocalTime,
    ): Alarm = assertIs<Outcome.Success<Alarm>>(runBlocking { app.koin.get<SaveAlarm>()(AlarmDraft(time = time)) }).value

    private fun ringSavedAlarm(app: WakeApp) {
        // The alarms are editable once the stored session is restored (the session lock, Story 2.6).
        runBlocking { app.engine.restore() }
        app.ring(AlarmFired(save(app, LocalTime(7, 0)).id, scheduledAt))
        app.awaitRinging()
    }

    @Test
    fun `the ring and the wake screen's first frame do not wait for the refresh, which then fills the cache`() {
        val gate = CompletableDeferred<Unit>()
        val source = FakeProductDetailsSource().apply { this.gate = gate }
        val app = WakeApp(userLock = FakeUserLockState(unlocked = true), productDetails = source)
        val cached = aPriceSnapshot(1..5)
        assertIs<Outcome.Success<*>>(runBlocking { app.koin.get<PriceCacheStore>().update { cached } })
        try {
            runBlocking { app.engine.restore() }
            val first = save(app, LocalTime(7, 0))
            val second = save(app, LocalTime(7, 5))
            val service = app.ring(AlarmFired(first.id, scheduledAt))
            app.awaitRinging()
            app.awaitUntil("the session start asked Play for the prices") { source.requests.isNotEmpty() }

            // The service is free while Play is still answering: its next command (a second alarm, merged) runs now.
            val session = assertIs<SessionState.Ringing>(app.engine.state.value).session
            service.withIntent(WakeService.alarmIntent(app.app, AlarmFired(second.id, scheduledAt))).startCommand(0, 2)
            assertEquals(second.id, app.awaitMerges(session.sessionId).single().alarmId)
            assertEquals(0, source.completed, "the next command ran while Play was still answering")
            assertTrue(app.mediaPlayers.last().isPlaying, "the alarm sound plays")

            launchActivity<WakeActivity>(Intent(app.app, WakeActivity::class.java)).use {
                composeRule.onNodeWithText("I'm up").assertExists()
                assertEquals(0, source.completed, "the first frame came while Play was still answering")
                assertEquals(
                    cached,
                    runBlocking {
                        app.koin
                            .get<PriceCatalog>()
                            .observe()
                            .first()
                    },
                    "the cache reads at once",
                )
            }
            assertEquals(listOf(SnoozeProducts.all), source.requests, "one refresh, of all 50 products")
        } finally {
            gate.complete(Unit)
        }

        app.awaitUntil("the refresh stored Play's prices") {
            runBlocking {
                app.koin
                    .get<PriceCatalog>()
                    .observe()
                    .first()
            }.entries.size == SnoozeProducts.all.size
        }
    }

    @Test
    fun `an alarm before the first unlock starts no refresh`() {
        val source = FakeProductDetailsSource()
        val app = WakeApp(userLock = FakeUserLockState(unlocked = false), productDetails = source)

        ringSavedAlarm(app)
        // A refresh would be launched right after the ring started; give it the time it would need.
        Thread.sleep(SETTLE_MILLIS)

        assertEquals(emptyList(), source.requests)
    }

    @Test
    fun `a test alarm starts no refresh`() {
        val source = FakeProductDetailsSource()
        val testAlarms = FakeTestAlarmStore(pending = aSessionConfig(label = "test", testMode = true))
        val app = WakeApp(userLock = FakeUserLockState(unlocked = true), productDetails = source, testAlarms = testAlarms)

        app.startService(WakeService.intent(app.app, WakeService.ACTION_TEST))
        app.awaitRinging()
        assertTrue(assertIs<SessionState.Ringing>(app.engine.state.value).session.config.testMode)
        Thread.sleep(SETTLE_MILLIS)

        assertEquals(emptyList(), source.requests)
    }

    private companion object {
        const val SETTLE_MILLIS = 500L
    }
}

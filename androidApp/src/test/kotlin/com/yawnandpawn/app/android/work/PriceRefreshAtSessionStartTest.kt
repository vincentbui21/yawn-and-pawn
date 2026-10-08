package com.yawnandpawn.app.android.work

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.wake.WakeActivity
import com.yawnandpawn.app.android.wake.WakeApp
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.billing.PriceCacheStore
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.billing.SnoozeProducts
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.launchActivity
import com.yawnandpawn.app.testing.FakeProductDetailsSource
import com.yawnandpawn.app.testing.FakeUserLockState
import com.yawnandpawn.app.testing.aPriceSnapshot
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

    private fun ringSavedAlarm(app: WakeApp) {
        // The alarms are editable once the stored session is restored (the session lock, Story 2.6).
        runBlocking { app.engine.restore() }
        val saved = assertIs<Outcome.Success<Alarm>>(runBlocking { app.koin.get<SaveAlarm>()(AlarmDraft(time = LocalTime(7, 0))) })
        app.ring(AlarmFired(saved.value.id, Instant.parse("2027-03-08T06:00:00Z")))
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
            ringSavedAlarm(app)
            app.awaitUntil("the session start asked Play for the prices") { source.requests.isNotEmpty() }

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

    private companion object {
        const val SETTLE_MILLIS = 500L
    }
}

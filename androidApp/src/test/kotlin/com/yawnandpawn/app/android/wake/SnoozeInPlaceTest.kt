package com.yawnandpawn.app.android.wake

import android.content.Intent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionReducer
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.SnoozeAvailabilityPolicy
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.launchActivity
import com.yawnandpawn.app.testing.FakeConnectivity
import com.yawnandpawn.app.testing.FakeProductDetailsSource
import com.yawnandpawn.app.testing.aSessionConfig
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Story 4.7: the wake screen combines the session with the live env, so the snooze control changes in place (the same
 * activity, still resumed) when the connection comes and goes, and the reducer accepts a tap exactly when the screen
 * offers one. "I'm up" stays enabled throughout.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SnoozeInPlaceTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val connectivity = FakeConnectivity(online = false)

    private val now = TimeSnapshot(wallMillis = 0, elapsedMillis = 0, bootCount = 1)

    /** Waits (bounded) until [text] is on screen, as a label or as what TalkBack reads. */
    private fun awaitShown(text: String) =
        composeRule.waitUntil(APP_WORK_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasText(text) or hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()
        }

    private fun SessionReducer.offersOnTap(state: SessionState): Boolean =
        reduce(state, SessionEvent.SnoozeTapped, now).effects.any { it is SessionEffect.ShowSnoozeConfirm }

    @Test
    fun `the snooze control follows the connection in place, and the reducer agrees with the screen`() {
        // Play's prices in the cache (the fake source answers every product at "USD {n}.00").
        val app = WakeApp(connectivity = connectivity, productDetails = FakeProductDetailsSource())
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<PriceCatalog>().refresh() })
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(), beforeFirstUnlock = false))
        val scenario = launchActivity<WakeActivity>(Intent(app.app, WakeActivity::class.java))
        var before: WakeActivity? = null
        scenario.onActivity { before = it }

        awaitShown("Snooze unavailable, offline")
        val ringing = assertIs<SessionState.Ringing>(app.engine.state.value)
        val reducer = app.koin.get<SessionReducer>()
        assertFalse(reducer.offersOnTap(ringing), "offline: a tap is ignored")

        connectivity.online = true
        awaitShown("Snooze · USD 1.00")
        composeRule.onNodeWithText("Snooze · USD 1.00").assertIsEnabled()
        assertIs<SnoozeAvailability.Available>(app.koin.get<SnoozeAvailabilityPolicy>().availability(ringing.session))
        assertTrue(reducer.offersOnTap(ringing), "online with a price: the tap opens the confirm sheet")

        connectivity.online = false
        awaitShown("Snooze unavailable, offline")
        composeRule.onNodeWithText("I'm up").assertIsEnabled()
        scenario.onActivity { now ->
            assertSame(before, now, "the same activity instance, not recreated")
            assertFalse(now.isFinishing)
        }
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
    }

    @Test
    fun `prices arriving while the wake screen is in front change the snooze in place (review fix)`() {
        connectivity.online = true
        val app = WakeApp(connectivity = connectivity, productDetails = FakeProductDetailsSource())
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(), beforeFirstUnlock = false))
        val scenario = launchActivity<WakeActivity>(Intent(app.app, WakeActivity::class.java))
        var before: WakeActivity? = null
        scenario.onActivity { before = it }
        awaitShown("Snooze unavailable, prices not loaded yet")

        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<PriceCatalog>().refresh() })

        awaitShown("Snooze · USD 1.00")
        composeRule.onNodeWithText("Snooze · USD 1.00").assertIsEnabled()
        scenario.onActivity { now -> assertSame(before, now, "the same activity instance, not recreated") }
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
    }
}

package com.yawnandpawn.app.android.wake

import android.content.Intent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.billing.SnoozeProducts
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.session.PurchaseFailureKind
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.PurchaseVerdict
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.SnoozeAvailabilityPolicy
import com.yawnandpawn.app.core.session.SnoozeOffer
import com.yawnandpawn.app.core.session.UnavailableReason
import com.yawnandpawn.app.core.session.UnlockPort
import com.yawnandpawn.app.core.session.UnlockResult
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.FakeBilling
import com.yawnandpawn.app.testing.FakeBillingCountry
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeDisplayPrices
import com.yawnandpawn.app.testing.FakeLivePriceSource
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.testing.aSessionConfig
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Story 4.14: every payment outcome on the real wake screen, over the real engine and the 4.13 sheet. Each one that
 * charged nothing says so with its EXPERIENCE.md message, polite for TalkBack, above "I'm up" (or in the check footer),
 * at least 10 s and then until the next tap; "I'm up" and the check work at once while it shows, and the ring goes on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PaymentOutcomeMessagesTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    /** Story 4.7's shape: snooze N + 1 on sale, unless offline ([online] off) or a payment is pending. */
    @Volatile
    private var online = true
    private val policy =
        SnoozeAvailabilityPolicy { session ->
            val number = session.snoozesGranted + 1
            when {
                session.paymentPending -> SnoozeAvailability.Unavailable(UnavailableReason.PaymentPending)
                !online -> SnoozeAvailability.Unavailable(UnavailableReason.Offline)
                else -> SnoozeAvailability.Available(SnoozeOffer(SnoozeProducts.idOf(number), number))
            }
        }

    /** An unlocked phone: Pay goes straight to Play. */
    private object Unlocked : UnlockPort {
        override fun isKeyguardLocked(): Boolean = false

        override suspend fun requestUnlock(): UnlockResult = awaitCancellation()
    }

    private val store = FakeActiveSessionStore()
    private val billing = FakeBilling()
    private val monotonic = FakeMonotonicClock(elapsedMillis = 1_000_000)
    private val wall = FakeClock()

    private fun app() =
        WakeApp(
            store = store,
            policy = policy,
            unlock = Unlocked,
            livePrices = FakeLivePriceSource(),
            displayPrices = FakeDisplayPrices(),
            billingCountry = FakeBillingCountry(),
            clock = wall,
            monotonic = monotonic,
            billing = billing,
            purchaseIntents = store.intents,
        )

    /** A ring with the default Math check; [onCheck] goes past "I'm up" to the check. */
    private fun ring(
        app: WakeApp,
        onCheck: Boolean = false,
    ) {
        val config = aSessionConfig().copy(checkPlan = CheckPlan.default())
        app.dispatch(SessionEvent.AlarmFired("session-1", config, beforeFirstUnlock = false))
        if (onCheck) app.dispatch(SessionEvent.ImUpTapped)
    }

    private fun launch(app: WakeApp) = ActivityScenario.launch<WakeActivity>(Intent(app.app, WakeActivity::class.java))

    private fun host(app: WakeApp): ConfirmSheetHost = app.koin.get()

    private fun session(app: WakeApp): SessionData = assertIs<SessionState.Ring>(app.engine.state.value).session

    private fun await(
        app: WakeApp,
        what: String,
        condition: () -> Boolean,
    ) = composeRule.awaitScreen(app, what, condition)

    /** Snooze, past the sheet's 500 ms guard, then Pay: the payment is in flight (Play's sheet is open). */
    private fun pay(app: WakeApp) {
        composeRule.onNodeWithText("Snooze · $1.00").performClick()
        await(app, "the sheet opens") {
            host(app).request.value != null && composeRule.onAllNodes(hasText("Snooze for 9 min?")).fetchSemanticsNodes().isNotEmpty()
        }
        monotonic.advanceBy(GUARD_MILLIS.milliseconds)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Pay $1.00 and snooze").performClick()
        await(app, "the payment is in flight") { session(app).paying != null }
    }

    /** Play answers [event] for the payment in flight (as Story 4.11's coordinator dispatches it). */
    private fun answer(
        app: WakeApp,
        event: SessionEvent,
    ) {
        app.dispatch(event)
        await(app, "the outcome") { session(app).paying == null && host(app).message.value != null }
    }

    /** The message on screen: polite for TalkBack, with no action of its own. */
    private fun assertMessage(text: String) {
        val node = composeRule.onNodeWithText(text).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion), "$text is announced politely")
        assertNull(node.config.getOrNull(SemanticsActions.OnClick), "$text has no action")
    }

    /** Lets the message's 10 s minimum pass on the screen's clock. */
    private fun passMinimum() {
        composeRule.mainClock.advanceTimeBy(WakeActivity.MESSAGE_MINIMUM.inWholeMilliseconds + FRAME_MILLIS)
        composeRule.waitForIdle()
    }

    @Test
    fun `cancelled, error and offline each say No charge above I'm up, the newest replacing the last, and the ring goes on`() {
        val app = app()
        ring(app)
        launch(app).use {
            listOf(
                SessionEvent.PurchaseCancelled to CANCELLED,
                SessionEvent.PurchaseFailed(PurchaseFailureKind.Error) to ERROR,
                SessionEvent.PurchaseFailed(PurchaseFailureKind.Offline) to OFFLINE,
            ).forEach { (event, text) ->
                pay(app)
                answer(app, event)
                assertMessage(text)
                MESSAGES.filter { it != text }.forEach { other -> composeRule.onNodeWithText(other).assertDoesNotExist() }
                assertTrue(text.endsWith("No charge."))
                // In the flow above "I'm up", never over it.
                val message = composeRule.onNodeWithText(text).getBoundsInRoot()
                val imUp = composeRule.onNodeWithText("I'm up").getBoundsInRoot()
                assertTrue(message.bottom <= imUp.top, "$text above I'm up: $message, $imUp")
                assertIs<SessionState.Ringing>(app.engine.state.value)
                assertTrue(app.lastMediaPlayer().isReallyPlaying, "the sound goes on")
                composeRule.onNodeWithText("Snooze · $1.00").assertExists()
            }
        }
    }

    @Test
    fun `I'm up works at once while a message shows, and the message stays its 10 s on the check`() {
        val app = app()
        ring(app)
        launch(app).use {
            pay(app)
            answer(app, SessionEvent.PurchaseCancelled)
            assertMessage(CANCELLED)

            composeRule.onNodeWithText("I'm up").performClick()
            await(app, "I'm up went through") { app.engine.state.value is SessionState.Grace }
            composeRule.onNodeWithText("Problem 1 of 3").assertExists()
            assertMessage(CANCELLED)

            passMinimum()
            composeRule.onNodeWithText(CANCELLED).assertDoesNotExist()
        }
    }

    @Test
    fun `over a check the message sits in the footer above Snooze, the keys work at once, and after 10 s the next tap ends it`() {
        val app = app()
        ring(app, onCheck = true)
        launch(app).use {
            pay(app)
            answer(app, SessionEvent.PurchaseFailed(PurchaseFailureKind.Error))
            assertMessage(ERROR)
            composeRule.onNodeWithText("Problem 1 of 3").assertExists()
            assertIs<SessionState.Grace>(app.engine.state.value)
            val message = composeRule.onNodeWithText(ERROR).getBoundsInRoot()
            val snooze = composeRule.onNodeWithText("Snooze · $1.00").getBoundsInRoot()
            assertTrue(message.bottom <= snooze.top, "the message above Snooze: $message, $snooze")

            // No tap during the minimum: the message outlives it and goes at the next tap, which also types.
            passMinimum()
            assertMessage(ERROR)
            composeRule.onNode(hasText("7") and hasClickAction()).performClick()
            composeRule.waitForIdle()
            composeRule.onNode(hasContentDescription("Answer 7")).assertExists()
            composeRule.onNodeWithText(ERROR).assertDoesNotExist()
        }
    }

    @Test
    fun `a key tap during the minimum types at once, and the message then ends with its 10 s`() {
        val app = app()
        ring(app, onCheck = true)
        launch(app).use {
            pay(app)
            answer(app, SessionEvent.PurchaseFailed(PurchaseFailureKind.Offline))
            composeRule.onNode(hasText("7") and hasClickAction()).performClick()
            composeRule.waitForIdle()
            composeRule.onNode(hasContentDescription("Answer 7")).assertExists()
            assertMessage(OFFLINE)

            passMinimum()
            composeRule.onNodeWithText(OFFLINE).assertDoesNotExist()
        }
    }

    @Test
    fun `offline makes snooze unavailable, and connectivity coming back re-enables it in place with no new message`() {
        val app = app()
        ring(app)
        launch(app).use {
            pay(app)
            online = false
            answer(app, SessionEvent.PurchaseFailed(PurchaseFailureKind.Offline))
            assertMessage(OFFLINE)
            composeRule.onNode(hasText("offline", substring = true), useUnmergedTree = true).assertExists()
            val shown = assertNotNull(host(app).message.value)

            online = true
            // The live policy (Story 4.7) re-renders the button on its own; here a new session state (a tap a second
            // later moves the interaction deadline) does it.
            wall.advanceBy(1.seconds)
            monotonic.advanceBy(1.seconds)
            app.dispatch(SessionEvent.UserInteracted)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Snooze · $1.00").assertExists()
            assertEquals(shown, host(app).message.value, "no new message")
            assertMessage(OFFLINE)
        }
    }

    @Test
    fun `pending says how it goes on and snooze is unavailable for this session`() {
        val app = app()
        ring(app)
        launch(app).use {
            pay(app)
            answer(app, SessionEvent.PurchasePending)
            assertMessage(PENDING)
            composeRule.onNode(hasText("payment pending", substring = true), useUnmergedTree = true).assertExists()
            composeRule.onNodeWithText("I'm up").assertExists()
            assertTrue(app.lastMediaPlayer().isReallyPlaying)
        }
    }

    @Test
    fun `a paid snooze shows no message`() {
        val app = app()
        ring(app)
        launch(app).use {
            pay(app)
            app.dispatch(SessionEvent.PurchaseGranted(SnoozeProducts.idOf(1), PurchaseToken("token-1"), PurchaseVerdict.Grant))
            await(app, "snoozed") { app.engine.state.value is SessionState.Snoozed }
            assertNull(host(app).message.value)
        }
    }

    private companion object {
        const val GUARD_MILLIS = 500L
        const val FRAME_MILLIS = 100L
        const val CANCELLED = "Payment cancelled. No charge."
        const val ERROR = "Payment didn't go through. No charge."
        const val OFFLINE = "No connection. No charge."
        const val PENDING =
            "Payment not confirmed yet. If it goes through before you finish, your snooze starts. Otherwise, finish the check to stop the alarm."
        val MESSAGES = listOf("Phone still locked. No charge.", CANCELLED, ERROR, OFFLINE, PENDING)
    }
}

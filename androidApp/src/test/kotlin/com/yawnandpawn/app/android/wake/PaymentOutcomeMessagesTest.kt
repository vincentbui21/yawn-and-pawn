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
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.core.billing.InstallIdProvider
import com.yawnandpawn.app.core.billing.LaunchResult
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.billing.PurchaseCoordinator
import com.yawnandpawn.app.core.billing.PurchaseUpdate
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.PurchaseFailureKind
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.UnlockPort
import com.yawnandpawn.app.core.session.UnlockResult
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.FakeBilling
import com.yawnandpawn.app.testing.FakeBillingCountry
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeConnectivity
import com.yawnandpawn.app.testing.FakeLivePriceSource
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.testing.FakeProductDetailsSource
import com.yawnandpawn.app.testing.aPurchaseSnapshot
import com.yawnandpawn.app.testing.aSessionConfig
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Story 4.14: every payment outcome on the real wake screen, from Play's own results (`LaunchResult`, `PurchaseUpdate`)
 * through the real `PurchaseCoordinator`, `SessionEngine` and `ConfirmSheetHost`, with Story 4.7's live snooze policy.
 * Each one that charged nothing says so with its EXPERIENCE.md message, polite for TalkBack, above "I'm up" (or in the
 * check footer), at least 10 s and then until the next tap; "I'm up" and the check work at once while it shows, and the
 * ring goes on. A lost Play result never says "No charge.", and a paid snooze shows no message.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PaymentOutcomeMessagesTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    /** An unlocked phone: Pay goes straight to Play. */
    private object Unlocked : UnlockPort {
        override fun isKeyguardLocked(): Boolean = false

        override suspend fun requestUnlock(): UnlockResult = awaitCancellation()
    }

    private val store = FakeActiveSessionStore()
    private val billing = FakeBilling()
    private val connectivity = FakeConnectivity(online = true)
    private val monotonic = FakeMonotonicClock(elapsedMillis = 1_000_000)
    private val wall = FakeClock()

    /** The real app with Play's prices cached (Story 4.7's policy offers Snooze), Play faked by [billing]. */
    private fun app(): WakeApp {
        val app =
            WakeApp(
                store = store,
                unlock = Unlocked,
                livePrices = FakeLivePriceSource(),
                productDetails = FakeProductDetailsSource(),
                connectivity = connectivity,
                billingCountry = FakeBillingCountry(),
                clock = wall,
                monotonic = monotonic,
                billing = billing,
                purchaseIntents = store.intents,
            )
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<PriceCatalog>().refresh() })
        // As the app does at start: the coordinator collects Play's purchase updates (the test graph is new).
        app.koin.get<PurchaseCoordinator>().start()
        return app
    }

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

    private fun session(app: WakeApp): SessionData = assertIs<SessionState.Active>(app.engine.state.value).session

    private fun installId(app: WakeApp): String =
        assertIs<Outcome.Success<String>>(runBlocking { app.koin.get<InstallIdProvider>().installId() }).value

    private fun await(
        app: WakeApp,
        what: String,
        condition: () -> Boolean,
    ) = composeRule.awaitScreen(app, what, condition)

    private val snoozeOnSale = hasText("Snooze · ", substring = true) and hasClickAction()

    /** Waits (bounded) until [text] is on screen, as a label or as what TalkBack reads. */
    private fun awaitShown(text: String) =
        composeRule.waitUntil(APP_WORK_TIMEOUT_MILLIS) {
            val shown = hasText(text, substring = true) or hasContentDescription(text, substring = true)
            composeRule.onAllNodes(shown).fetchSemanticsNodes().isNotEmpty()
        }

    /**
     * Snooze, past the sheet's 500 ms guard, then Pay: the coordinator launches Play ([billing]) and its result is [launch]
     * (the sheet stays open on Launched, for a later [PurchaseUpdate]).
     */
    private fun pay(
        app: WakeApp,
        launch: LaunchResult = LaunchResult.Launched,
    ) {
        val launches = billing.launched.size
        billing.launchResults.add(Outcome.Success(launch))
        composeRule.onNode(snoozeOnSale).performClick()
        await(app, "the sheet opens") {
            host(app).request.value != null && composeRule.onAllNodes(hasText("Snooze for 9 min?")).fetchSemanticsNodes().isNotEmpty()
        }
        monotonic.advanceBy(GUARD_MILLIS.milliseconds)
        composeRule.waitForIdle()
        composeRule.onNode(hasText(" and snooze", substring = true)).performClick()
        await(app, "Play was launched") { billing.launched.size == launches + 1 }
    }

    /** Play's open sheet answers [update]; the outcome reaches the screen. */
    private fun answer(
        app: WakeApp,
        update: PurchaseUpdate,
    ) {
        assertTrue(billing.deliver(update))
        await(app, "the outcome") { session(app).paying == null && host(app).message.value != null }
    }

    /** No payment message is on screen. */
    private fun assertNoMessage() = MESSAGES.forEach { composeRule.onNodeWithText(it).assertDoesNotExist() }

    /** The message on screen: polite for TalkBack, with no action of its own, and the only one. */
    private fun assertMessage(text: String) {
        val node = composeRule.onNodeWithText(text).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion), "$text is announced politely")
        assertNull(node.config.getOrNull(SemanticsActions.OnClick), "$text has no action")
        MESSAGES.filter { it != text }.forEach { other -> composeRule.onNodeWithText(other).assertDoesNotExist() }
    }

    /** Lets the message's 10 s minimum pass on the screen's clock. */
    private fun passMinimum() {
        composeRule.mainClock.advanceTimeBy(WakeActivity.MESSAGE_MINIMUM.inWholeMilliseconds + FRAME_MILLIS)
        composeRule.waitForIdle()
    }

    /** Moves both time ports by [duration] and lets the engine's deadline tick run (the snooze is over; its slot then fires). */
    private fun pass(
        app: WakeApp,
        duration: Duration,
    ) {
        wall.advanceBy(duration)
        monotonic.advanceBy(duration)
        val job = app.koin.get<ApplicationScope>().launch { app.engine.tick() }
        app.awaitUntil("the tick") { job.isCompleted }
    }

    /** The message each failure kind shows: "No charge." for all but a lost result. */
    private fun textOf(kind: PurchaseFailureKind): String =
        when (kind) {
            PurchaseFailureKind.Offline -> OFFLINE
            PurchaseFailureKind.UnlockFailed -> UNLOCK_FAILED
            PurchaseFailureKind.Error -> ERROR
            PurchaseFailureKind.Unconfirmed -> PENDING
        }

    @Test
    fun `every Play result from the launch or the sheet shows its message, and none shows while the next payment is in flight`() {
        val app = app()
        ring(app)
        launch(app).use {
            val fromLaunch =
                listOf(LaunchResult.Cancelled to CANCELLED) + PurchaseFailureKind.entries.map { LaunchResult.Failed(it) to textOf(it) }
            val fromSheet =
                listOf(PurchaseUpdate.Cancelled to CANCELLED) + PurchaseFailureKind.entries.map { PurchaseUpdate.Failed(it) to textOf(it) }
            fromLaunch.forEach { (result, text) ->
                pay(app, result)
                await(app, "$result") { session(app).paying == null && host(app).message.value != null }
                assertMessage(text)
            }
            fromSheet.forEach { (update, text) ->
                // Within the earlier message's 10 s: the new payment hides it while Play's sheet is open.
                pay(app)
                assertNotNull(session(app).paying)
                assertNoMessage()
                answer(app, update)
                assertMessage(text)
                // In the flow above "I'm up", never over it; the ring goes on and Snooze is still on sale.
                val message = composeRule.onNodeWithText(text).getBoundsInRoot()
                val imUp = composeRule.onNodeWithText("I'm up").getBoundsInRoot()
                assertTrue(message.bottom <= imUp.top, "$text above I'm up: $message, $imUp")
                assertIs<SessionState.Ringing>(app.engine.state.value)
                assertTrue(app.lastMediaPlayer().isReallyPlaying, "the sound goes on")
                composeRule.onNode(snoozeOnSale).assertExists()
            }
        }
    }

    @Test
    fun `a sheet whose result never came says the pending copy, never No charge, and a late payment still snoozes`() {
        val app = app()
        ring(app)
        launch(app).use {
            pay(app)
            monotonic.advanceBy(PurchaseCoordinator.STALE_SHEET)
            app.koin.get<PurchaseCoordinator>().onWakeScreenResumed()
            await(app, "the lost result") { session(app).paying == null && host(app).message.value != null }
            assertMessage(PENDING)
            assertTrue(composeRule.onAllNodes(hasText("No charge", substring = true)).fetchSemanticsNodes().isEmpty(), "no No charge.")

            // Play's PURCHASED comes after all: it grants, and no message survives the ring.
            assertTrue(billing.buy(aPurchaseSnapshot(productId = "snooze_usd_01", profileId = "session-1", accountId = installId(app))))
            await(app, "snoozed") { app.engine.state.value is SessionState.Snoozed }
            assertNull(host(app).message.value)
        }
        pass(app, 9.minutes + 1.seconds)
        app.dispatch(SessionEvent.SlotFired)
        assertIs<SessionState.Ringing>(app.engine.state.value)
        launch(app).use {
            awaitShown("I'm up")
            assertNoMessage()
        }
    }

    @Test
    fun `a paid snooze after a cancelled one shows no message, on Snoozed or on the next ring`() {
        val app = app()
        ring(app)
        launch(app).use {
            pay(app, LaunchResult.Cancelled)
            await(app, "cancelled") { host(app).message.value != null }
            assertMessage(CANCELLED)

            pay(app)
            assertTrue(billing.buy(aPurchaseSnapshot(productId = "snooze_usd_01", profileId = "session-1", accountId = installId(app))))
            await(app, "snoozed") { app.engine.state.value is SessionState.Snoozed }
            assertNull(host(app).message.value)
        }
        pass(app, 9.minutes + 1.seconds)
        app.dispatch(SessionEvent.SlotFired)
        assertIs<SessionState.Ringing>(app.engine.state.value)
        launch(app).use {
            awaitShown("I'm up")
            assertNoMessage()
            assertNull(host(app).messageFor(session(app), host(app).message.value))
        }
    }

    @Test
    fun `I'm up works at once while a message shows, and the message stays its 10 s on the check`() {
        val app = app()
        ring(app)
        launch(app).use {
            pay(app, LaunchResult.Cancelled)
            await(app, "cancelled") { host(app).message.value != null }
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
            answer(app, PurchaseUpdate.Failed(PurchaseFailureKind.Error))
            assertMessage(ERROR)
            composeRule.onNodeWithText("Problem 1 of 3").assertExists()
            assertIs<SessionState.Grace>(app.engine.state.value)
            val message = composeRule.onNodeWithText(ERROR).getBoundsInRoot()
            val snooze = composeRule.onNode(snoozeOnSale).getBoundsInRoot()
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
            answer(app, PurchaseUpdate.Failed(PurchaseFailureKind.Error))
            composeRule.onNode(hasText("7") and hasClickAction()).performClick()
            composeRule.waitForIdle()
            composeRule.onNode(hasContentDescription("Answer 7")).assertExists()
            assertMessage(ERROR)

            passMinimum()
            composeRule.onNodeWithText(ERROR).assertDoesNotExist()
        }
    }

    @Test
    fun `offline makes snooze unavailable, and the connection coming back re-enables it in place with no new message`() {
        val app = app()
        ring(app)
        launch(app).use {
            pay(app)
            connectivity.online = false
            answer(app, PurchaseUpdate.Failed(PurchaseFailureKind.Offline))
            assertMessage(OFFLINE)
            awaitShown("Snooze unavailable, offline")
            val shown = assertNotNull(host(app).message.value)

            // Story 4.7's live env re-renders the button by itself: no tap, no engine event.
            connectivity.online = true
            composeRule.waitUntil(APP_WORK_TIMEOUT_MILLIS) { composeRule.onAllNodes(snoozeOnSale).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(shown, host(app).message.value, "no new message")
            assertMessage(OFFLINE)
        }
    }

    @Test
    fun `a pending payment says how it goes on and snooze is unavailable for this session`() {
        val app = app()
        ring(app)
        launch(app).use {
            pay(app)
            val pending =
                aPurchaseSnapshot(productId = "snooze_usd_01", profileId = "session-1", accountId = installId(app), pending = true)
            billing.own(pending)
            answer(app, PurchaseUpdate.Purchases(listOf(pending)))
            assertMessage(PENDING)
            awaitShown("payment pending")
            composeRule.onNodeWithText("I'm up").assertExists()
            assertTrue(app.lastMediaPlayer().isReallyPlaying)
        }
    }

    private companion object {
        const val GUARD_MILLIS = 500L
        const val FRAME_MILLIS = 100L
        const val UNLOCK_FAILED = "Phone still locked. No charge."
        const val CANCELLED = "Payment cancelled. No charge."
        const val ERROR = "Payment didn't go through. No charge."
        const val OFFLINE = "No connection. No charge."
        const val PENDING =
            "Payment not confirmed yet. If it goes through before you finish, your snooze starts. " +
                "Otherwise, finish the check to stop the alarm."
        val MESSAGES = listOf(UNLOCK_FAILED, CANCELLED, ERROR, OFFLINE, PENDING)
    }
}

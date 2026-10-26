package com.yawnandpawn.app.android.wake

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.billing.InstallIdProvider
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PurchaseCoordinator
import com.yawnandpawn.app.core.billing.SnoozeProducts
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.PurchaseVerdict
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.SnoozeAvailabilityPolicy
import com.yawnandpawn.app.core.session.SnoozeOffer
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.FakeBilling
import com.yawnandpawn.app.testing.FakeBillingCountry
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeDisplayPrices
import com.yawnandpawn.app.testing.FakeLivePriceSource
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.testing.aPurchaseSnapshot
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.testing.rightAnswer
import com.yawnandpawn.app.ui.format.formatClockTime
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Story 4.15 on the real wake screen over the real engine: after a paid snooze the screen says "Snoozed. Next ring at
 * {time}." for 3 s and closes without touching the armed slot; the re-ring shows "Snooze {n} of {max} · {paid} paid
 * this morning" with the next price, and "I'm up" and the check work as on a first ring; a snooze from a check drops
 * its progress and the re-ring starts a fresh check with a new grace window; Success says what was paid.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi", shadows = [RecordingNotificationManager::class])
class SnoozedScreenTest {
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
    private val billing = FakeBilling()
    private val wall = FakeClock()
    private val monotonic = FakeMonotonicClock(elapsedMillis = 1_000_000)
    private val dollar = Money.of(1, "USD")

    private fun app() =
        WakeApp(
            store = store,
            policy = policy,
            livePrices = FakeLivePriceSource(),
            displayPrices = FakeDisplayPrices(),
            billingCountry = FakeBillingCountry(),
            clock = wall,
            monotonic = monotonic,
            billing = billing,
            purchaseIntents = store.intents,
        )

    private fun ring(
        app: WakeApp,
        check: Boolean = false,
    ) {
        val config = if (check) aSessionConfig().copy(checkPlan = CheckPlan.default()) else aSessionConfig()
        app.dispatch(SessionEvent.AlarmFired("session-1", config, beforeFirstUnlock = false))
        if (check) app.dispatch(SessionEvent.ImUpTapped)
    }

    private fun launch(app: WakeApp) = ActivityScenario.launch<WakeActivity>(Intent(app.app, WakeActivity::class.java))

    private fun await(
        app: WakeApp,
        what: String,
        condition: () -> Boolean,
    ) = composeRule.awaitScreen(app, what, condition)

    private fun shown(text: String): Boolean = shown(text, substring = false)

    /** Play's grant of snooze [number] at [price] (what the coordinator sends with the intent's price). */
    private fun grant(
        app: WakeApp,
        number: Int = 1,
        price: Money = Money.of(number, "USD"),
    ) = app.dispatch(
        SessionEvent.PurchaseGranted(SnoozeProducts.idOf(number), PurchaseToken("token-$number"), PurchaseVerdict.Grant, price = price),
    )

    private fun manager(app: WakeApp) = app.app.getSystemService(NotificationManager::class.java)

    /** The ringing notification posted now. */
    private fun posted(app: WakeApp): Notification = assertNotNull(NotificationPosts.wakeNotification(manager(app)))

    private fun installId(app: WakeApp): String =
        assertIs<Outcome.Success<String>>(runBlocking { app.koin.get<InstallIdProvider>().installId() }).value

    private fun awaitSnoozed(app: WakeApp) =
        await(app, "Snoozed shows") { app.engine.state.value is SessionState.Snoozed && shown("Snoozed.", substring = true) }

    private fun snoozed(app: WakeApp): SessionData = assertIs<SessionState.Snoozed>(app.engine.state.value).session

    /** "Snoozed. Next ring at {time}." for the snooze end, as the phone writes times. */
    private fun snoozedText(
        app: WakeApp,
        is24Hour: Boolean = false,
    ): String {
        val end = assertNotNull(snoozed(app).snoozeEnd)
        val zone = app.koin.get<TimeZoneProvider>().current()
        val time = Instant.fromEpochMilliseconds(end.wallMillis).toLocalDateTime(zone).time
        return "Snoozed. Next ring at ${formatClockTime(time, is24Hour)}."
    }

    /** Moves both time ports by [duration] and lets the engine's deadline tick run. */
    private fun pass(
        app: WakeApp,
        duration: Duration,
    ) {
        wall.advanceBy(duration)
        monotonic.advanceBy(duration)
        val job = app.koin.get<ApplicationScope>().launch { app.engine.tick() }
        app.awaitUntil("the tick") { job.isCompleted }
    }

    /** The snooze ends: the slot fires and the session rings again (ring [index]). */
    private fun snoozeEnds(
        app: WakeApp,
        index: Int = 2,
    ) {
        pass(app, 9.minutes)
        app.dispatch(SessionEvent.SlotFired)
        app.awaitUntil("ring $index") { (app.engine.state.value as? SessionState.Ringing)?.session?.ringIndex == index }
    }

    private fun slotArmed(app: WakeApp): Boolean =
        shadowOf(app.app.getSystemService(AlarmManager::class.java))
            .scheduledAlarms
            .any { shadowOf(it.operation).requestCode == RequestCodes.SESSION_SLOT }

    @Test
    fun `a paid snooze shows the next ring time for 3 s, then closes and stops keeping the screen on, the slot armed`() {
        val app = app()
        ring(app)
        launch(app).use { scenario ->
            lateinit var screen: WakeActivity
            scenario.onActivity { screen = it }
            grant(app)
            awaitSnoozed(app)

            composeRule.onNodeWithText(snoozedText(app)).assertExists()
            val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
            composeRule.onNode(hasText(snoozedText(app)) and polite).assertExists()
            composeRule.onNodeWithText("I'm up").assertDoesNotExist()
            assertFalse(app.lastMediaPlayer().isReallyPlaying, "the sound stopped")
            assertTrue(slotArmed(app), "the next ring is armed")

            composeRule.mainClock.autoAdvance = false
            composeRule.mainClock.advanceTimeBy(WakeActivity.SNOOZED_SCREEN_TIME.inWholeMilliseconds - HALF_SECOND)
            assertFalse(screen.isFinishing, "still shown before 3 s")
            assertTrue(screen.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)

            composeRule.mainClock.advanceTimeBy(HALF_SECOND + FRAME)
            assertTrue(screen.isFinishing, "closed after 3 s")
            val keepOn = screen.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            assertEquals(0, keepOn, "the system turns the screen off")

            // The system then stops and destroys the finishing screen (Robolectric leaves that step to the scenario).
            scenario.moveToState(Lifecycle.State.DESTROYED)
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
            assertIs<SessionState.Snoozed>(app.engine.state.value, "closing sent nothing")
            assertTrue(slotArmed(app), "the next ring is still armed")
            assertNull(posted(app).fullScreenIntent, "nothing brings the screen back during the snooze")
            val posts = NotificationPosts(app.app)
            val beforeReRing = posts.mark()

            composeRule.mainClock.autoAdvance = true
            snoozeEnds(app)
            assertNotNull(posted(app).fullScreenIntent, "the re-ring opens the wake screen")
            // Story 5.13 device check: the full one replaces the quiet one as a new post, never as an update.
            posts.assertNewFullScreenPost(beforeReRing, "the snooze-end re-ring after the Snoozed screen closed")
        }
    }

    @Test
    fun `a slot that fires right as the Snoozed note closes still rings with the full-screen intent`() {
        val app = app()
        ring(app)
        launch(app).use { scenario ->
            lateinit var screen: WakeActivity
            scenario.onActivity { screen = it }
            grant(app)
            awaitSnoozed(app)
            // The snooze is over; its slot fires as the note's 3 s run out.
            wall.advanceBy(9.minutes)
            monotonic.advanceBy(9.minutes)
            composeRule.mainClock.autoAdvance = false
            composeRule.mainClock.advanceTimeBy(WakeActivity.SNOOZED_SCREEN_TIME.inWholeMilliseconds + FRAME)
            assertTrue(screen.isFinishing, "the note closed")
            val posts = NotificationPosts(app.app)
            val beforeReRing = posts.mark()
            // The slot fires before the system has stopped the closing screen: the ring step finds it still shown.
            app.dispatch(SessionEvent.SlotFired)
            assertIs<SessionState.Ringing>(app.engine.state.value)

            scenario.moveToState(Lifecycle.State.DESTROYED)
            assertNotNull(posted(app).fullScreenIntent, "the ring brings the wake screen back at once")
            // Story 5.13 device check: as a new post, so SystemUI launches it.
            posts.assertNewFullScreenPost(beforeReRing, "the Snoozed screen finishing exactly at the snooze end")
            posts.assertAtMostOneShown(manager(app))
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun `with the 24-hour setting the next ring time is a 24-hour time`() {
        val app = app()
        Settings.System.putString(app.app.contentResolver, Settings.System.TIME_12_24, "24")
        ring(app)
        launch(app).use {
            grant(app)
            awaitSnoozed(app)
            composeRule.onNodeWithText(snoozedText(app, is24Hour = true)).assertExists()
        }
    }

    @Test
    fun `the re-ring shows the session line and next price, read between clock and I'm up, and Success says what was paid`() {
        val app = app()
        ring(app)
        grant(app)
        snoozeEnds(app)
        launch(app).use {
            await(app, "the re-ring") { shown("I'm up") }
            composeRule.onNodeWithText("Snooze 1 of 5 · $1.00 paid this morning").assertExists()
            composeRule.onNodeWithText("Snooze · $2.00").assertExists()

            val order = traversal(composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
            val line = order.indexOf("Snooze 1 of 5 · $1.00 paid this morning")
            val imUp = order.indexOf("I'm up")
            assertTrue(line in 1 until imUp, "clock, then the session line, then I'm up: $order")

            composeRule.onNodeWithText("I'm up").performClick()
            await(app, "Success") { app.engine.state.value == SessionState.Idle && shown("You're up. That's what counts.") }
            composeRule.onNodeWithText("$1.00 paid this morning").assertExists()
        }
    }

    @Test
    fun `a payment granted by the coordinator fills the session line and Success with the intent's price`() {
        val app = app()
        app.koin.get<PurchaseCoordinator>().start()
        ring(app)
        launch(app).use {
            composeRule.onNodeWithText("Snooze · $1.00").performClick()
            await(app, "the sheet opens") { shown("Snooze for 9 min?") }
            monotonic.advanceBy(GUARD.milliseconds)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Pay $1.00 and snooze").performClick()
            app.awaitUntil("Play's sheet opens") { billing.launched.isNotEmpty() }

            // Play reports the purchase for this session and install: the coordinator reconciles and grants it.
            val snapshot =
                aPurchaseSnapshot(token = "real-1", productId = SnoozeProducts.idOf(1), profileId = "session-1", accountId = installId(app))
            assertTrue(billing.buy(snapshot))
            awaitSnoozed(app)
            assertEquals(listOf(dollar), snoozed(app).paid, "the intent's live price")
        }
        snoozeEnds(app)
        launch(app).use {
            await(app, "the re-ring") { shown("I'm up") }
            composeRule.onNodeWithText("Snooze 1 of 5 · $1.00 paid this morning").assertExists()
        }
    }

    @Test
    fun `after two snoozes paid in two currencies the line and Success show one total per currency`() {
        val app = app()
        ring(app)
        grant(app, number = 1, price = dollar)
        snoozeEnds(app, index = 2)
        grant(app, number = 2, price = Money(1_190_000, "EUR"))
        snoozeEnds(app, index = 3)
        launch(app).use {
            await(app, "the third ring") { shown("I'm up") }
            composeRule.onNodeWithText("Snooze 2 of 5 · $1.00 + €1.19 paid this morning").assertExists()
            composeRule.onNodeWithText("Snooze · $3.00").assertExists()

            composeRule.onNodeWithText("I'm up").performClick()
            await(app, "Success") { app.engine.state.value == SessionState.Idle && shown("You're up. That's what counts.") }
            composeRule.onNodeWithText("$1.00 + €1.19 paid this morning").assertExists()
        }
    }

    /**
     * The session saw a pending payment; Play's latest list ([listed]) still holds it, or not. Success says "Your pending
     * payment wasn't used. Google refunds it automatically." only in the first case (Story 4.15 review).
     */
    private fun pendingNote(listed: Boolean): Boolean {
        val app = app()
        ring(app)
        launch(app).use {
            await(app, "the ring") { shown("I'm up") }
            if (listed) {
                billing.own(aPurchaseSnapshot(token = "pending-1", profileId = "session-1", accountId = installId(app), pending = true))
            }
            app.dispatch(SessionEvent.PurchasePending)
            val coordinator = app.koin.get<PurchaseCoordinator>()
            assertTrue(runBlocking { coordinator.recover() }, "the recovery query ran")
            assertEquals(listed, "session-1" in coordinator.pendingSessions.value)
            composeRule.onNodeWithText("I'm up").performClick()
            await(app, "Success") { app.engine.state.value == SessionState.Idle && shown("Up on time.") }
            return shown("Your pending payment wasn't used. Google refunds it automatically.")
        }
    }

    @Test
    fun `Success promises a refund for a pending payment Play still lists`() = assertTrue(pendingNote(listed = true))

    @Test
    fun `Success promises no refund for a pending payment Play no longer lists`() = assertFalse(pendingNote(listed = false))

    @Test
    fun `a snooze from a check drops its progress, and the re-ring starts a fresh check with a new grace window`() {
        val app = app()
        ring(app, check = true)
        val before = assertIs<SessionState.Grace>(app.engine.state.value).session
        app.dispatch(SessionEvent.CheckAnswerSubmitted(assertNotNull(rightAnswer(before.checkRun))))
        launch(app).use { scenario ->
            lateinit var screen: WakeActivity
            scenario.onActivity { screen = it }
            await(app, "problem 2") { shown("Problem 2 of 3") }
            // The footer's snooze opens the confirm sheet (Story 4.13), whose Pay ends in Play's grant.
            composeRule.onNodeWithText("Snooze · $1.00").performClick()
            await(app, "the sheet opens") { shown("Snooze for 9 min?") }
            grant(app)
            awaitSnoozed(app)
            assertEquals(0, snoozed(app).checkRun.step.entry, "progress dropped")
            composeRule.onNodeWithText("Problem 2 of 3").assertDoesNotExist()
            composeRule.onNodeWithText("Snooze for 9 min?").assertDoesNotExist()
            composeRule.mainClock.autoAdvance = false
            composeRule.mainClock.advanceTimeBy(WakeActivity.SNOOZED_SCREEN_TIME.inWholeMilliseconds + FRAME)
            assertTrue(screen.isFinishing)
        }

        snoozeEnds(app)
        val again = assertIs<SessionState.Ringing>(app.engine.state.value).session
        assertNotEquals(before.checkRun.seeds, again.checkRun.seeds, "new seeds")
        composeRule.mainClock.autoAdvance = true
        launch(app).use {
            await(app, "the re-ring") { shown("I'm up") }
            composeRule.onNodeWithText("Snooze 1 of 5 · $1.00 paid this morning").assertExists()
            composeRule.onNodeWithText("I'm up").performClick()
            await(app, "a new grace window on problem 1") {
                app.engine.state.value is SessionState.Grace && shown("Problem 1 of 3")
            }
            val grace = assertIs<SessionState.Grace>(app.engine.state.value).session
            assertTrue(assertNotNull(grace.graceEnd).wallMillis > assertNotNull(before.graceEnd).wallMillis, "a new grace window")
            composeRule.onNodeWithText("Snooze · $2.00").assertExists()
        }
    }

    @Test
    fun `a pending payment that clears while the user is mid-check snoozes with no tap`() {
        val app = app()
        ring(app, check = true)
        app.dispatch(SessionEvent.PurchasePending)
        launch(app).use {
            await(app, "the check") { shown("Problem 1 of 3") }
            grant(app)
            awaitSnoozed(app)
            composeRule.onNodeWithText(snoozedText(app)).assertExists()
            assertFalse(snoozed(app).paymentPending)
        }
    }

    @Test
    fun `a merged alarm during the Snoozed note rings in place with the same session line and the screen stays`() {
        val app = app()
        ring(app)
        launch(app).use { scenario ->
            lateinit var screen: WakeActivity
            scenario.onActivity { screen = it }
            grant(app)
            await(app, "Snoozed") { shown("Snoozed.", substring = true) }
            app.dispatch(SessionEvent.OverlapAlarmFired("alarm-b", aSessionConfig().scheduledAt + 1.minutes))
            await(app, "the merged ring") { app.engine.state.value is SessionState.Ringing && shown("I'm up") }
            composeRule.onNodeWithText("Snooze 1 of 5 · $1.00 paid this morning").assertExists()
            assertEquals(listOf(dollar), assertIs<SessionState.Ringing>(app.engine.state.value).session.paid, "no fee")

            composeRule.mainClock.autoAdvance = false
            composeRule.mainClock.advanceTimeBy(WakeActivity.SNOOZED_SCREEN_TIME.inWholeMilliseconds + FRAME)
            assertFalse(screen.isFinishing, "the ring keeps its screen")
            composeRule.mainClock.autoAdvance = true
            composeRule.onNodeWithText("I'm up").performClick()
            await(app, "I'm up works") { app.engine.state.value !is SessionState.Ringing }
        }
    }

    private fun shown(
        text: String,
        substring: Boolean,
    ): Boolean =
        composeRule
            .onAllNodes(hasText(text, substring = substring))
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .isNotEmpty()

    /**
     * The texts in TalkBack's order: sorted by traversal index (stable, so equal indexes keep the tree's pre-order), as
     * the screen's nodes all sit under one traversal group.
     */
    private fun traversal(root: SemanticsNode): List<String> =
        flatten(root)
            .sortedBy { it.second }
            .map { it.first }

    private fun flatten(
        node: SemanticsNode,
        inherited: Float = 0f,
    ): List<Pair<String, Float>> {
        // A text inside a button is read with the button, at the button's place.
        val index = node.config.getOrNull(SemanticsProperties.TraversalIndex) ?: inherited
        val text =
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
        val own = listOfNotNull(text?.let { it to index })
        return own + node.children.flatMap { flatten(it, index) }
    }

    private companion object {
        const val GUARD = 500L
        const val HALF_SECOND = 500L
        val FRAME = 100.milliseconds.inWholeMilliseconds
    }
}

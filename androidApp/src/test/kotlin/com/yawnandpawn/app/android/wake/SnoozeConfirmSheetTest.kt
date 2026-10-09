package com.yawnandpawn.app.android.wake

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.SnoozeProducts
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.PurchaseVerdict
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEngine
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
import com.yawnandpawn.app.testing.usdLivePrice
import com.yawnandpawn.app.ui.wake.SheetRequest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Story 4.13: the snooze confirm sheet on the real wake screen, over the real engine: it opens from the snooze button,
 * ignores taps for 500 ms, pays with Play's live price and a new intent id, asks to unlock first on a locked phone,
 * and every way of closing it goes back to the ring with no charge and "I'm up" in reach.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SnoozeConfirmSheetTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    /** The Epic 4 policy's shape: snooze N + 1 on sale, unless [snoozeOn] is off (offline). */
    private var snoozeOn = true
    private val policy =
        SnoozeAvailabilityPolicy { session ->
            if (snoozeOn) {
                val number = session.snoozesGranted + 1
                SnoozeAvailability.Available(SnoozeOffer(SnoozeProducts.idOf(number), number))
            } else {
                SnoozeAvailability.Unavailable(UnavailableReason.Offline)
            }
        }
    private val live = FakeLivePriceSource()
    private val keyguard = Keyguard()

    /** The keyguard: [locked], or [unreadable] (every read throws). Unlocking always succeeds when asked. */
    private class Keyguard : UnlockPort {
        @Volatile
        var locked = false

        @Volatile
        var unreadable = false

        override fun isKeyguardLocked(): Boolean = if (unreadable) error("keyguard unreadable") else locked

        // The PIN prompt stays up until the test resumes the screen (a wrong PIN, or a lost callback, sends nothing).
        override suspend fun requestUnlock(): UnlockResult = awaitCancellation()
    }

    private val store = FakeActiveSessionStore()
    private val country = FakeBillingCountry()
    private val billing = FakeBilling()
    private val reuse = EngineReuseChoices()

    /** Story 4.11's reuse choices, as the coordinator makes them: the offered product's events to the engine. */
    private class EngineReuseChoices : ReuseChoices {
        var engine: SessionEngine? = null
        var offered: String? = null

        override suspend fun acceptReuse() {
            offered?.let { engine?.dispatch(SessionEvent.ReuseAccepted(it, PurchaseToken("token-$it"))) }
        }

        override suspend fun declineReuse() {
            offered?.let { engine?.dispatch(SessionEvent.ReuseDeclined(it)) }
        }
    }

    /** The coordinator offers the stranded payment for [productId] (`ReuseOffered`, Story 4.11). */
    private fun offerReuse(
        app: WakeApp,
        productId: String,
    ) {
        reuse.engine = app.engine
        reuse.offered = productId
        app.dispatch(SessionEvent.ReuseOffered(productId, PurchaseToken("token-$productId"), PurchaseVerdict.OfferReuse))
    }

    /** The app's time ports, moved only by the test: the sheet's guard reads [monotonic] (review fix 2). */
    private val wall = FakeClock()
    private val monotonic = FakeMonotonicClock(elapsedMillis = 1_000_000)

    private fun app() =
        WakeApp(
            store = store,
            policy = policy,
            unlock = keyguard,
            livePrices = live,
            displayPrices = FakeDisplayPrices(),
            reuseChoices = reuse,
            billingCountry = country,
            clock = wall,
            monotonic = monotonic,
            // Play's sheet opens and stays open: no result until the test sends one.
            billing = billing,
            // The coordinator reads the committed intent from the same store the engine writes it to.
            purchaseIntents = store.intents,
        )

    /** Moves both time ports by [duration] and lets the engine's deadline tick run. */
    private fun pass(
        app: WakeApp,
        duration: kotlin.time.Duration,
    ) {
        wall.advanceBy(duration)
        monotonic.advanceBy(duration)
        val job = app.koin.get<ApplicationScope>().launch { app.engine.tick() }
        app.awaitUntil("the tick") { job.isCompleted }
        composeRule.waitForIdle()
    }

    private fun ring(
        app: WakeApp,
        check: Boolean = false,
    ) {
        val config = if (check) aSessionConfig().copy(checkPlan = CheckPlan.default()) else aSessionConfig()
        app.dispatch(SessionEvent.AlarmFired("session-1", config, beforeFirstUnlock = false))
        if (check) app.dispatch(SessionEvent.ImUpTapped)
    }

    private fun launch(app: WakeApp) = ActivityScenario.launch<WakeActivity>(Intent(app.app, WakeActivity::class.java))

    private fun host(app: WakeApp): ConfirmSheetHost = app.koin.get()

    private fun session(app: WakeApp): SessionData = assertIs<SessionState.Ring>(app.engine.state.value).session

    private fun await(
        app: WakeApp,
        what: String,
        condition: () -> Boolean,
    ) = composeRule.awaitScreen(app, what, condition)

    /** Taps "Snooze · $1.00" and waits for the sheet. */
    private fun openSheet(app: WakeApp) {
        composeRule.onNodeWithText("Snooze · $1.00").performClick()
        await(app, "the sheet opens") {
            host(app).request.value != null &&
                composeRule.onAllNodes(hasText("Snooze for 9 min?")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Lets the sheet's 500 ms input guard pass on the app's monotonic clock (only that clock). */
    private fun passGuard(millis: Long = GUARD_MILLIS) {
        monotonic.advanceBy(millis.milliseconds)
        composeRule.waitForIdle()
    }

    private fun pay(price: String = "$1.00") = composeRule.onNodeWithText("Pay $price and snooze")

    private fun intents() = store.writes

    @Test
    fun `Snooze opens the sheet with the price, the next price and the nudge, over the ringing screen`() {
        val app = app()
        ring(app)
        launch(app).use {
            openSheet(app)
            composeRule.onNodeWithText("$1.00").assertExists()
            composeRule.onNodeWithText("This one costs $1.00. The next one costs $2.00.").assertExists()
            composeRule.onNodeWithText("Is 9 more minutes worth $1.00? You've got this.").assertExists()
            composeRule.onNodeWithText("Google Play shows the final total, including any tax.").assertDoesNotExist()
            composeRule.onNodeWithText("I'll get up").assertExists()
            // The live price of this snooze and the next, asked at once (in any order).
            assertEquals(setOf("snooze_usd_01", "snooze_usd_02"), live.asked.toSet())
            assertIs<SessionState.Ringing>(app.engine.state.value, "the alarm keeps ringing")
            assertTrue(app.lastMediaPlayer().isReallyPlaying)
        }
    }

    @Test
    fun `the tax note shows for a US billing country`() {
        country.country = "US"
        val app = app()
        ring(app)
        launch(app).use {
            openSheet(app)
            composeRule.onNodeWithText("Google Play shows the final total, including any tax.").assertExists()
        }
    }

    @Test
    fun `taps count only after 500 ms on the app clock, then Pay sends PayConfirmed with the live price and a new intent id`() {
        val app = app()
        ring(app)
        launch(app).use {
            openSheet(app)
            pay().performClick()
            composeRule.onNodeWithText("I'll get up").performClick()
            passGuard(millis = GUARD_MILLIS - 1)
            pay().performClick()
            composeRule.onNodeWithText("I'll get up").performClick()
            composeRule.waitForIdle()
            app.koin.get<ApplicationScope>().awaitChildren()
            assertNull(session(app).paying, "taps up to 499 ms do nothing")
            composeRule.onNodeWithText("Snooze for 9 min?").assertExists()

            passGuard(millis = 1)
            pay().performClick()
            await(app, "the Pay reached the engine") { session(app).paying != null }

            val paying = assertNotNull(session(app).paying)
            assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(paying.value), "a UUID v4")
            val intent = store.intents.saved.single()
            assertEquals(Money.of(1, "USD"), intent.price)
            assertEquals("$1.00", intent.formattedPrice, "Play's live text")
            app.awaitUntil("billing launched for the intent") { billing.launched.map { it.intentId } == listOf(paying) }
            composeRule.onNodeWithText("Snooze for 9 min?").assertDoesNotExist()
            composeRule.onNodeWithText("I'm up").assertExists()
        }
    }

    @Test
    fun `each Pay tap writes a new intent id`() {
        val app = app()
        ring(app)
        launch(app).use {
            openSheet(app)
            passGuard()
            pay().performClick()
            await(app, "the first Pay") { session(app).paying != null }
            val first = session(app).paying
            app.dispatch(SessionEvent.PurchaseCancelled)
            await(app, "cancelled") { session(app).paying == null && host(app).request.value == null }

            openSheet(app)
            passGuard()
            pay().performClick()
            await(app, "the second Pay") { session(app).paying != null }
            assertNotEquals(first, session(app).paying)
            assertEquals(2, store.intents.saved.size)
        }
    }

    @Test
    fun `I'll get up, Back and a swipe down close the sheet with no charge and no message, and the ring goes on`() {
        val app = app()
        ring(app)
        launch(app).use { scenario ->
            listOf<() -> Unit>(
                { composeRule.onNodeWithText("I'll get up").performClick() },
                { scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() } },
                {
                    composeRule.onNodeWithText("Snooze for 9 min?").performTouchInput {
                        swipeDown(
                            startY = centerY,
                            endY =
                                centerY + 160.dp.toPx(),
                        )
                    }
                },
            ).forEachIndexed { index, close ->
                openSheet(app)
                passGuard()
                close()
                await(app, "closed by way $index") { host(app).request.value == null }
                composeRule.onNodeWithText("Snooze for 9 min?").assertDoesNotExist()
                composeRule.onNodeWithText("Phone still locked. No charge.").assertDoesNotExist()
                composeRule.onNodeWithText("I'm up").assertExists()
            }
            assertTrue(intents().isEmpty(), "no intent written")
            assertNull(session(app).paying)
            assertIs<SessionState.Ringing>(app.engine.state.value)
            assertTrue(app.lastMediaPlayer().isReallyPlaying, "the alarm keeps ringing")
            scenario.onActivity { assertTrue(!it.isFinishing, "Back closed only the sheet") }

            composeRule.onNodeWithText("I'm up").performClick()
            await(app, "I'm up still works") { app.engine.state.value !is SessionState.Ringing }
        }
    }

    @Test
    fun `on a locked phone Pay asks to unlock first, and Cancel says Phone still locked with no charge`() {
        keyguard.locked = true
        val app = app()
        ring(app)
        launch(app).use {
            openSheet(app)
            passGuard()
            pay().performClick()
            await(app, "the unlock step") { session(app).unlocking }
            composeRule.onNodeWithText("Unlock to pay $1.00").assertExists()

            passGuard()
            composeRule.onNodeWithText("Cancel").performClick()
            await(app, "the unlock failed") { session(app).paying == null }
            composeRule.onNodeWithText("Phone still locked. No charge.").assertExists()
            composeRule.onNodeWithText("Unlock to pay $1.00").assertDoesNotExist()
            assertTrue(app.lastMediaPlayer().isReallyPlaying, "the sound continues")

            // The next tap ends the message; snooze is still offered.
            composeRule.onNodeWithText("Snooze · $1.00").performClick()
            await(app, "the sheet opens again") { host(app).request.value != null }
            composeRule.onNodeWithText("Phone still locked. No charge.").assertDoesNotExist()
        }
    }

    @Test
    fun `back on screen unlocked with the unlock pending, the coordinator launches billing and the unlock step goes`() {
        keyguard.locked = true
        val app = app()
        ring(app)
        launch(app).use { scenario ->
            openSheet(app)
            passGuard()
            pay().performClick()
            await(app, "the unlock step") { session(app).unlocking }
            composeRule.onNodeWithText("Unlock to pay $1.00").assertExists()

            // The PIN prompt took the screen and its callback was lost: Story 4.11's coordinator resolves it on resume.
            scenario.moveToState(Lifecycle.State.STARTED)
            keyguard.locked = false
            scenario.moveToState(Lifecycle.State.RESUMED)
            await(app, "UnlockSucceeded") { !session(app).unlocking }
            assertNotNull(session(app).paying, "billing launched for the same intent")
            composeRule.onNodeWithText("Unlock to pay $1.00").assertDoesNotExist()
            composeRule.onNodeWithText("I'm up").assertExists()
        }
    }

    @Test
    fun `a live price that changed is shown first, the guard starts again, and Pay then charges the new price`() {
        val app = app()
        ring(app)
        launch(app).use {
            openSheet(app)
            live.prices["snooze_usd_01"] = usdLivePrice("snooze_usd_01").copy(price = Money(1_500_000, "USD"), formattedPrice = "$1.50")
            passGuard()
            pay().performClick()
            await(app, "the new price shows") { composeRule.onAllNodes(hasText("Pay $1.50 and snooze")).fetchSemanticsNodes().isNotEmpty() }
            assertNull(session(app).paying, "nothing charged at a price the user did not see")

            pay("$1.50").performClick()
            composeRule.waitForIdle()
            assertNull(session(app).paying, "the guard started again")

            passGuard()
            pay("$1.50").performClick()
            await(app, "the Pay") { session(app).paying != null }
            assertEquals(
                Money(1_500_000, "USD"),
                store.intents.saved
                    .single()
                    .price,
            )
        }
    }

    @Test
    fun `with no live price at Pay the sheet closes with no charge`() {
        val app = app()
        ring(app)
        launch(app).use {
            openSheet(app)
            live.prices["snooze_usd_01"] = null
            passGuard()
            pay().performClick()
            await(app, "the sheet closes") { host(app).request.value == null }
            assertNull(session(app).paying)
            assertTrue(intents().isEmpty())
            composeRule.onNodeWithText("I'm up").assertExists()
        }
    }

    @Test
    fun `Pay after snooze just became unavailable closes the sheet, and the button says why`() {
        val app = app()
        ring(app)
        launch(app).use {
            openSheet(app)
            passGuard()
            snoozeOn = false
            pay().performClick()
            await(app, "the sheet closes") { host(app).request.value == null }
            assertNull(session(app).paying)
            // A tap redraws the screen with the policy's reason.
            pass(app, 1.seconds)
            app.dispatch(SessionEvent.UserInteracted)
            composeRule.waitForIdle()
            composeRule.onNode(hasText("offline", substring = true), useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun `over a check the sheet opens from the footer and I'll get up returns to the same problem`() {
        val app = app()
        ring(app, check = true)
        launch(app).use {
            composeRule.onNodeWithText("Problem 1 of 3").assertExists()
            openSheet(app)
            passGuard()
            composeRule.onNodeWithText("I'll get up").performClick()
            await(app, "closed") { host(app).request.value == null }
            composeRule.onNodeWithText("Problem 1 of 3").assertExists()
            assertIs<SessionState.Grace>(app.engine.state.value)
        }
    }

    @Test
    fun `already paid uses the earlier payment, or Not now declines it`() {
        val app = app()
        ring(app)
        launch(app).use {
            offerReuse(app, "snooze_usd_01")
            await(app, "already paid") { host(app).request.value != null }
            composeRule.onNodeWithText("You already paid $1.00 earlier that wasn't used. Use it for this snooze?").assertExists()
            passGuard()
            composeRule.onNodeWithText("Not now").performClick()
            await(app, "declined") { session(app).declinedReuseProduct == "snooze_usd_01" }

            offerReuse(app, "snooze_usd_01")
            await(app, "already paid again") { host(app).request.value != null }
            passGuard()
            composeRule.onNodeWithText("Use it").performClick()
            await(app, "snoozed") { app.engine.state.value is SessionState.Snoozed }
        }
    }

    @Test
    fun `TalkBack meets the title first as a heading, and neither button is focused`() {
        val app = app()
        ring(app)
        launch(app).use {
            openSheet(app)
            composeRule.onNode(hasText("Snooze for 9 min?") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
            composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Snooze for 9 min?")).assertExists()
            pay().assertIsNotFocused()
            composeRule.onNodeWithText("I'll get up").assertIsNotFocused()
        }
    }

    @Test
    fun `in the pane TalkBack reads the title, then the price, then Pay, and a new price is announced`() {
        val app = app()
        ring(app)
        launch(app).use {
            openSheet(app)
            val paneTitle = SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)
            val pane = composeRule.onNode(paneTitle, useUnmergedTree = true).fetchSemanticsNode()
            val order = preOrderTexts(pane)
            val title = order.indexOfFirst { it == "Snooze for 9 min?" }
            val price = order.indexOfFirst { it == "$1.00" }
            val payAt = order.indexOfFirst { it == "Pay $1.00 and snooze" }
            assertTrue(title in 0 until price && price < payAt, "$order")
            composeRule
                .onNode(hasText("$1.00") and SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
                .assertExists()
        }
    }

    @Test
    fun `the sheet closes by itself when snooze becomes unavailable, and a new ring after a paid snooze shows none`() {
        val app = app()
        ring(app)
        launch(app).use {
            openSheet(app)
            snoozeOn = false
            pass(app, 1.seconds)
            app.dispatch(SessionEvent.UserInteracted)
            await(app, "closed with no tap") { host(app).request.value == null }
            composeRule.onNodeWithText("Snooze for 9 min?").assertDoesNotExist()

            snoozeOn = true
            pass(app, 1.seconds)
            app.dispatch(SessionEvent.UserInteracted)
            openSheet(app)
            passGuard()
            pay().performClick()
            await(app, "paying") { session(app).paying != null }
            app.dispatch(SessionEvent.PurchaseGranted("snooze_usd_01", PurchaseToken("token-1"), PurchaseVerdict.Grant))
            await(app, "snoozed") { app.engine.state.value is SessionState.Snoozed && host(app).request.value == null }

            pass(app, 9.minutes)
            app.dispatch(SessionEvent.SlotFired)
            await(app, "the next ring") { (app.engine.state.value as? SessionState.Ringing)?.session?.ringIndex == 2 }
            composeRule.onNodeWithText("Snooze for 9 min?").assertDoesNotExist()
            composeRule.onNodeWithText("I'm up").assertExists()
        }
    }

    @Test
    fun `a tap outside counts only after 500 ms too, and TalkBack's dismiss closes the sheet with no charge`() {
        val app = app()
        ring(app)
        launch(app).use {
            openSheet(app)
            val outside = { composeRule.onRoot().performTouchInput { click(Offset(centerX, OUTSIDE_Y)) } }
            passGuard(millis = GUARD_MILLIS - 1)
            outside()
            composeRule.waitForIdle()
            assertNotNull(host(app).request.value, "499 ms: the tap outside is ignored")
            passGuard(millis = 1)
            outside()
            await(app, "closed by a tap outside") { host(app).request.value == null }

            openSheet(app)
            passGuard()
            composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).performSemanticsAction(SemanticsActions.Dismiss)
            await(app, "closed by TalkBack") { host(app).request.value == null }
            assertTrue(intents().isEmpty())
            assertIs<SessionState.Ringing>(app.engine.state.value)
            assertTrue(app.lastMediaPlayer().isReallyPlaying)
        }
    }

    @Test
    fun `an earlier payment offered over confirm switches to already paid with a fresh guard, and Back or a swipe declines it`() {
        val app = app()
        ring(app)
        launch(app).use { scenario ->
            openSheet(app)
            passGuard()
            offerReuse(app, "snooze_usd_01")
            await(app, "already paid") { host(app).request.value is SheetRequest.AlreadyPaid }
            composeRule.onNodeWithText("Use it").performClick()
            composeRule.waitForIdle()
            app.koin.get<ApplicationScope>().awaitChildren()
            assertIs<SheetRequest.AlreadyPaid>(host(app).request.value, "the guard started again")

            passGuard()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await(app, "declined by Back") { session(app).declinedReuseProduct == "snooze_usd_01" }

            offerReuse(app, "snooze_usd_02")
            await(app, "already paid again") { host(app).request.value is SheetRequest.AlreadyPaid }
            passGuard()
            composeRule.onNodeWithText(ALREADY_PAID_TWO).performTouchInput { swipeDown(startY = centerY, endY = centerY + 160.dp.toPx()) }
            await(app, "declined by a swipe") { session(app).declinedReuseProduct == "snooze_usd_02" }
        }
    }

    @Test
    fun `the grace window ends while the sheet is open, the sheet stays and the alarm is back at full volume`() {
        val app = app()
        ring(app, check = true)
        launch(app).use {
            openSheet(app)
            assertTrue(app.player.isMuted, "quiet time")
            pass(app, 20.seconds)
            await(app, "Loud") { app.engine.state.value is SessionState.Loud }
            assertNotNull(host(app).request.value)
            composeRule.onNodeWithText("Snooze for 9 min?").assertExists()
            assertTrue(!app.player.isMuted, "the mute ended")
            assertEquals(1f, app.player.gain)
        }
    }

    @Test
    fun `with the shipped placeholder ports no sheet can open and the ring goes on`() {
        val app = WakeApp(policy = policy)
        ring(app)
        launch(app).use {
            composeRule.onNodeWithText("Prices not loaded yet", useUnmergedTree = true).assertExists()
            app.dispatch(SessionEvent.SnoozeTapped)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Snooze for 9 min?").assertDoesNotExist()
            composeRule.onNodeWithText("I'm up").assertExists()
            assertIs<SessionState.Ringing>(app.engine.state.value)
        }
    }

    @Test
    fun `a keyguard that cannot be read asks to unlock first, and the unlock message ends after 10 s`() {
        val app = app()
        ring(app)
        launch(app).use {
            openSheet(app)
            passGuard()
            keyguard.unreadable = true
            pay().performClick()
            await(app, "the unlock step") { session(app).unlocking }
            passGuard()
            composeRule.onNodeWithText("Cancel").performClick()
            await(app, "cancelled") { session(app).paying == null }
            composeRule.onNodeWithText("Phone still locked. No charge.").assertExists()

            composeRule.mainClock.advanceTimeBy(WakeActivity.MESSAGE_TIMEOUT.inWholeMilliseconds + FRAME_MILLIS)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Phone still locked. No charge.").assertDoesNotExist()
        }
    }

    /** The texts under [node] in TalkBack's reading order (pre-order of the unmerged tree). */
    private fun preOrderTexts(node: SemanticsNode): List<String> =
        listOfNotNull(node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }) +
            node.children.flatMap(::preOrderTexts)

    private companion object {
        const val GUARD_MILLIS = 500L
        const val FRAME_MILLIS = 100L

        /** A point near the top of the screen, on the scrim over the clock. */
        const val OUTSIDE_Y = 120f
        const val ALREADY_PAID_TWO = "You already paid $2.00 earlier that wasn't used. Use it for this snooze?"
    }
}

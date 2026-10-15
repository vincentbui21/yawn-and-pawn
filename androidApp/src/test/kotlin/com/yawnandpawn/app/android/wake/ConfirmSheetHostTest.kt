package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.core.billing.FeeLadder
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceEntry
import com.yawnandpawn.app.core.billing.UsdFeeLadder
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.EffectRunner
import com.yawnandpawn.app.core.session.EntryEffect
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.PurchaseOutcome
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.SnoozeOffer
import com.yawnandpawn.app.core.session.UnavailableReason
import com.yawnandpawn.app.testing.DEFAULT_FAKE_INSTANT
import com.yawnandpawn.app.testing.FakeDisplayPrices
import com.yawnandpawn.app.testing.FakeIdGenerator
import com.yawnandpawn.app.testing.FakeLivePriceSource
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakePurchaseIntentStore
import com.yawnandpawn.app.testing.aPurchaseIntent
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.usdLivePrice
import com.yawnandpawn.app.ui.wake.SheetRequest
import com.yawnandpawn.app.ui.wake.SnoozeSheet
import com.yawnandpawn.app.ui.wake.WakeMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Story 4.13: the confirm sheet's actions and effects, without a screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmSheetHostTest {
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val live = FakeLivePriceSource()
    private val choices = RecordingReuseChoices()

    /** The coordinator's reuse choices, recorded. */
    private class RecordingReuseChoices : ReuseChoices {
        val calls = mutableListOf<String>()

        override suspend fun acceptReuse() {
            calls += "accept"
        }

        override suspend fun declineReuse() {
            calls += "decline"
        }
    }

    private val logger = FakeLogger()
    private val intents = FakePurchaseIntentStore()

    private fun hostOn(
        scope: CoroutineScope,
        ladder: FeeLadder = UsdFeeLadder,
    ) = ConfirmSheetHost(live, FakeDisplayPrices(), choices, ladder, FakeIdGenerator(), intents, scope, logger)

    private val host = hostOn(scope)
    private val session = aSession()
    private val offer = SnoozeOffer("snooze_usd_01", 1)
    private val available = SnoozeAvailability.Available(offer)
    private val sent = mutableListOf<SessionEvent>()
    private val send: (List<SessionEvent>) -> Unit = { sent += it }
    private val dollar = Money.of(1, "USD")

    private fun open(
        on: SessionData = session,
        into: ConfirmSheetHost = host,
    ) = into.onEffect(SessionEffect.ShowSnoozeConfirm(offer), SessionState.Ringing(on))

    private fun shown(
        on: SessionData = session,
        from: ConfirmSheetHost = host,
    ) = from.sheet(on, available, from.request.value, from.livePrices.value, from.sentPrices.value, showTaxNote = false)

    /** The payment was cancelled in Play: its outcome closes the sheet. */
    private fun cancelled(on: ConfirmSheetHost = host) =
        on.onEffect(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Cancelled), SessionState.Ringing(session))

    @Test
    fun `ShowSnoozeConfirm opens a confirm for the ring and asks Play for this price and the next`() =
        scope.runTest {
            open()
            assertEquals(SheetRequest.Confirm(session.sessionId, session.ringIndex, offer), host.request.value)
            assertEquals(listOf("snooze_usd_01", "snooze_usd_02"), live.asked)
            assertEquals(SnoozeSheet.Confirm(9, dollar, Money.of(2, "USD")), shown())
        }

    @Test
    fun `no confirm opens while a payment is in flight or outside a ring`() =
        scope.runTest {
            open(session.copy(paying = PurchaseIntentId("intent-1")))
            assertNull(host.request.value)
            host.onEffect(SessionEffect.ShowSnoozeConfirm(offer), SessionState.Idle)
            assertNull(host.request.value)
        }

    @Test
    fun `Pay at the price on screen sends PayConfirmed with the live price and a new id each tap`() =
        scope.runTest {
            open()
            host.onUpper(session, available, dollar, send)
            cancelled()
            open()
            host.onUpper(session, available, dollar, send)
            val pays = sent.filterIsInstance<SessionEvent.PayConfirmed>()
            assertEquals(2, pays.size)
            assertEquals(usdLivePrice("snooze_usd_01"), pays[0].livePrice)
            assertTrue(pays[0].intentId != pays[1].intentId, "a new intent id per tap")
        }

    @Test
    fun `a live price other than the one on screen is shown instead of charged`() =
        scope.runTest {
            open()
            val higher = usdLivePrice("snooze_usd_01").copy(price = Money(1_500_000, "USD"), formattedPrice = "$1.50")
            live.prices["snooze_usd_01"] = higher
            host.onUpper(session, available, dollar, send)
            assertTrue(sent.none { it is SessionEvent.PayConfirmed })
            assertEquals(Money(1_500_000, "USD"), (shown() as SnoozeSheet.Confirm).price)

            host.onUpper(session, available, Money(1_500_000, "USD"), send)
            assertEquals(higher, sent.filterIsInstance<SessionEvent.PayConfirmed>().single().livePrice)
        }

    @Test
    fun `no live price at Pay closes the sheet with No connection, No charge (4_18 device check - never silent)`() =
        scope.runTest {
            open()
            live.prices["snooze_usd_01"] = null
            host.onUpper(session, available, dollar, send)
            assertNull(host.request.value)
            assertEquals(listOf<SessionEvent>(SessionEvent.UserInteracted), sent)
            assertTrue(logger.events.any { it.toString().contains("snooze live price") })
            assertEquals(WakeMessage.PaymentOffline, host.messageFor(session, host.message.value))
            assertTrue(LogEvent.ConfirmSheetClosed("confirm", "no live price at Pay") in logger.events, "${logger.events}")
        }

    @Test
    fun `Pay after snooze became unavailable closes the sheet and sends nothing`() =
        scope.runTest {
            open()
            host.onUpper(session, SnoozeAvailability.Unavailable(UnavailableReason.Offline), dollar, send)
            assertNull(host.request.value)
            assertTrue(sent.isEmpty())
            assertTrue(LogEvent.ConfirmSheetClosed("confirm", "Pay when snooze unavailable: Offline") in logger.events, "${logger.events}")
        }

    @Test
    fun `4_18 device check - the sheet stays open when the live price rewrites the cached price its offer carries`() =
        scope.runTest {
            val cached = PriceEntry("snooze_usd_01", "€1.09", Money(1_090_000, "EUR"), DEFAULT_FAKE_INSTANT)
            val opened = offer.copy(price = cached)
            host.onEffect(SessionEffect.ShowSnoozeConfirm(opened), SessionState.Ringing(session))
            val rewritten = SnoozeAvailability.Available(offer.copy(price = cached.copy(fetchedAt = DEFAULT_FAKE_INSTANT + 1.minutes)))

            host.follow(session, rewritten)

            assertIs<SheetRequest.Confirm>(host.request.value)
            assertIs<SnoozeSheet.Confirm>(shown(), "the sheet shows at the cached or live price")
            val sheetNow = host.sheet(session, rewritten, host.request.value, host.livePrices.value, host.sentPrices.value, false)
            assertIs<SnoozeSheet.Confirm>(sheetNow)
            assertTrue(logger.events.none { it is LogEvent.ConfirmSheetClosed }, "${logger.events}")
        }

    @Test
    fun `a confirm nobody can price closes with No connection, No charge instead of showing nothing`() =
        scope.runTest {
            live.prices["snooze_usd_01"] = null
            val unpriced =
                ConfirmSheetHost(live, FakeDisplayPrices(all = false), choices, UsdFeeLadder, FakeIdGenerator(), intents, scope, logger)
            open(into = unpriced)
            assertNull(shown(from = unpriced), "nothing to show: no price")

            unpriced.follow(session, available)

            assertNull(unpriced.request.value)
            assertEquals(WakeMessage.PaymentOffline, unpriced.messageFor(session, unpriced.message.value))
            assertTrue(LogEvent.ConfirmSheetClosed("confirm", "no price known") in logger.events, "${logger.events}")
        }

    @Test
    fun `every close is logged with its reason, and a sheet that cannot open says why`() =
        scope.runTest {
            open()
            host.follow(session, SnoozeAvailability.Unavailable(UnavailableReason.Offline))
            open()
            host.onImUp()
            open()
            host.onDismiss(session, send)
            open()
            host.follow(session.copy(ringIndex = 2), available)
            host.onEffect(SessionEffect.ShowSnoozeConfirm(offer), SessionState.Idle)
            open(session.copy(paying = PurchaseIntentId("intent-1")))

            assertEquals(
                listOf(
                    LogEvent.ConfirmSheetClosed("confirm", "snooze unavailable: Offline"),
                    LogEvent.ConfirmSheetClosed("confirm", "I'm up"),
                    LogEvent.ConfirmSheetClosed("confirm", "dismissed"),
                    LogEvent.ConfirmSheetClosed("confirm", "another ring"),
                    LogEvent.ConfirmSheetClosed("confirm", "not opened: not ringing"),
                    LogEvent.ConfirmSheetClosed("confirm", "not opened: payment in flight"),
                ),
                logger.events.filterIsInstance<LogEvent.ConfirmSheetClosed>(),
            )
            assertTrue(logger.events.none { "snooze_usd" in it.toString() }, "no product or price in the log")
        }

    @Test
    fun `I'll get up or I'm up while Pay waits for its price drops the Pay`() =
        scope.runTest {
            open()
            live.hold = true
            host.onUpper(session, available, dollar, send)
            host.onDismiss(session, send)
            live.release()
            assertEquals(listOf<SessionEvent>(SessionEvent.UserInteracted), sent)

            sent.clear()
            open()
            host.onUpper(session, available, dollar, send)
            host.onImUp()
            live.release()
            assertTrue(sent.isEmpty())
            assertNull(host.request.value)
        }

    @Test
    fun `the unlock step shows the sent price, Cancel sends UnlockFailed and its outcome shows the message for this ring only`() =
        scope.runTest {
            open()
            host.onUpper(session, available, dollar, send)
            val intent = sent.filterIsInstance<SessionEvent.PayConfirmed>().single().intentId
            val unlocking = session.copy(paying = intent, unlocking = true)
            assertEquals(SnoozeSheet.Unlocking(dollar), shown(unlocking))

            sent.clear()
            host.onDismiss(unlocking, send)
            assertEquals(listOf(SessionEvent.UserInteracted, SessionEvent.UnlockFailed), sent)

            host.onEffect(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.UnlockFailed), SessionState.Ringing(session))
            assertEquals(WakeMessage.UnlockFailed, host.messageFor(session, host.message.value))
            assertNull(host.messageFor(session.copy(ringIndex = 2), host.message.value))
            // Story 4.14: a tap during the 10 s minimum does not end it; the minimum's end then does.
            host.onTap()
            assertEquals(WakeMessage.UnlockFailed, host.messageFor(session, host.message.value))
            host.onMinimumShown(host.message.value!!)
            assertNull(host.message.value)
        }

    @Test
    fun `every outcome closes the sheet and shows its own message on this ring (Story 4_14)`() =
        scope.runTest {
            val expected =
                mapOf(
                    PurchaseOutcome.UnlockFailed to WakeMessage.UnlockFailed,
                    PurchaseOutcome.Cancelled to WakeMessage.PaymentCancelled,
                    PurchaseOutcome.Failed to WakeMessage.PaymentError,
                    PurchaseOutcome.Offline to WakeMessage.PaymentOffline,
                    PurchaseOutcome.Unconfirmed to WakeMessage.PaymentPending,
                )
            PurchaseOutcome.entries.forEach { outcome ->
                open()
                host.onEffect(SessionEffect.ShowPurchaseOutcome(outcome), SessionState.Ringing(session))
                assertNull(host.request.value, "$outcome closes the sheet")
                assertEquals(expected.getValue(outcome), host.messageFor(session, host.message.value), "$outcome")
            }
            open()
            host.onEffect(SessionEffect.ShowPaymentPending, SessionState.Ringing(session))
            assertNull(host.request.value)
            assertEquals(WakeMessage.PaymentPending, host.messageFor(session, host.message.value))
        }

    @Test
    fun `a message stays after its minimum until the next tap, and a newer one replaces it with its own minimum`() =
        scope.runTest {
            cancelled()
            val first = host.message.value!!
            host.onMinimumShown(first)
            assertEquals(WakeMessage.PaymentCancelled, host.messageFor(session, host.message.value), "no tap yet: it stays")

            // A newer outcome replaces it; the older showing's minimum no longer touches it.
            host.onEffect(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Offline), SessionState.Ringing(session))
            val second = host.message.value!!
            assertTrue(second.serial != first.serial)
            host.onMinimumShown(first)
            host.onTap()
            assertEquals(WakeMessage.PaymentOffline, host.messageFor(session, host.message.value), "inside its own minimum")
            host.onMinimumShown(second)
            assertNull(host.message.value, "the tap during the minimum ends it with the minimum")

            // The same message twice is two showings.
            cancelled()
            val again = host.message.value!!
            host.onMinimumShown(again)
            host.onTap()
            assertNull(host.message.value, "after the minimum the next tap ends it")
        }

    @Test
    fun `review - an earlier message is hidden while a payment is in flight, and a new Pay ends it`() =
        scope.runTest {
            cancelled()
            assertEquals(WakeMessage.PaymentCancelled, host.messageFor(session, host.message.value))
            assertNull(host.messageFor(session.copy(paying = PurchaseIntentId("intent-9")), host.message.value), "hidden while paying")

            open()
            host.onUpper(session, available, dollar, send)
            assertTrue(sent.any { it is SessionEvent.PayConfirmed })
            assertNull(host.message.value, "the Pay ended the earlier No charge.")
        }

    @Test
    fun `review - a lost Play result shows the pending message, never No charge`() =
        scope.runTest {
            host.onEffect(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Unconfirmed), SessionState.Ringing(session))
            assertEquals(WakeMessage.PaymentPending, host.messageFor(session, host.message.value))
        }

    @Test
    fun `review - the message ends when the session leaves the ring (a grant to Snoozed)`() =
        scope.runTest {
            cancelled()
            host.onEffect(SessionEffect.Consume(PurchaseToken("token-1")), SessionState.Snoozed(session))
            assertNull(host.message.value)
        }

    @Test
    fun `no message outside a ring, and none for the already-paid sheet`() =
        scope.runTest {
            host.onEffect(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Cancelled), SessionState.Idle)
            host.onEffect(SessionEffect.ShowPaymentPending, SessionState.Idle)
            assertNull(host.message.value)
            host.onEffect(SessionEffect.ShowReuseSheet("snooze_usd_01"), SessionState.Ringing(session))
            host.onEffect(SessionEffect.HideReuseSheet, SessionState.Ringing(session))
            assertNull(host.message.value)
        }

    @Test
    fun `one request sends one Pay, also when the engine has not shown it yet or the user taps twice while Play is asked`() =
        runTest {
            val standard = hostOn(this)
            open(into = standard)
            live.hold = true
            standard.onUpper(session, available, dollar, send)
            standard.onUpper(session, available, dollar, send)
            runCurrent()
            live.release()
            advanceUntilIdle()
            // The engine never applied it (the sheet still shows confirm): further taps are spent.
            live.hold = false
            standard.onUpper(session, available, dollar, send)
            standard.onUpper(session, available, dollar, send)
            advanceUntilIdle()
            assertEquals(1, sent.filterIsInstance<SessionEvent.PayConfirmed>().size, "$sent")

            // After the payment's outcome, a new opening is a new request: it may pay again.
            cancelled(standard)
            open(into = standard)
            standard.onUpper(session, available, dollar, send)
            advanceUntilIdle()
            assertEquals(2, sent.filterIsInstance<SessionEvent.PayConfirmed>().size)
        }

    @Test
    fun `no Pay slips in after I'm up or a dismiss, whatever the dispatcher order`() =
        runTest {
            val standard = hostOn(this)
            open(into = standard)
            standard.onUpper(session, available, dollar, send)
            standard.onImUp()
            advanceUntilIdle()
            open(into = standard)
            standard.onUpper(session, available, dollar, send)
            standard.onDismiss(session, send)
            advanceUntilIdle()
            assertEquals(listOf<SessionEvent>(SessionEvent.UserInteracted), sent)
        }

    @Test
    fun `a live price that never comes closes the sheet after the timeout with no charge`() =
        scope.runTest {
            open()
            live.hold = true
            host.onUpper(session, available, dollar, send)
            advanceTimeBy(ConfirmSheetHost.LIVE_PRICE_TIMEOUT - 1.milliseconds)
            assertIs<SheetRequest.Confirm>(host.request.value)
            advanceTimeBy(2.milliseconds)
            assertNull(host.request.value)
            assertEquals(listOf<SessionEvent>(SessionEvent.UserInteracted), sent)
            live.release()
        }

    @Test
    fun `the unlock step after a new process shows the price stored with the intent`() =
        scope.runTest {
            intents.put(aPurchaseIntent(intentId = "intent-9", price = Money.of(4, "USD")))
            val unlocking = session.copy(paying = PurchaseIntentId("intent-9"), unlocking = true)
            assertNull(shown(unlocking), "unknown until read")
            host.follow(unlocking, available)
            assertEquals(SnoozeSheet.Unlocking(Money.of(4, "USD")), shown(unlocking))
        }

    @Test
    fun `already paid uses the earlier payment and Not now declines it through the coordinator, and HideReuseSheet closes it`() =
        scope.runTest {
            val reuse = SessionEffect.ShowReuseSheet("snooze_usd_01")
            host.onEffect(reuse, SessionState.Ringing(session))
            assertEquals(SnoozeSheet.AlreadyPaid(dollar), shown())
            host.onUpper(session, available, null, send)
            assertEquals(listOf("accept"), choices.calls)
            assertNull(host.request.value)

            host.onEffect(reuse, SessionState.Ringing(session))
            host.onDismiss(session, send)
            assertEquals(listOf("accept", "decline"), choices.calls)
            assertTrue(sent.isEmpty(), "the token and the events stay in the coordinator: $sent")

            host.onEffect(reuse, SessionState.Ringing(session))
            host.onEffect(SessionEffect.HideReuseSheet, SessionState.Ringing(session))
            assertNull(host.request.value)
        }

    @Test
    fun `Use it after the sheet was closed does nothing`() =
        runTest {
            val standard = hostOn(this)
            standard.onEffect(SessionEffect.ShowReuseSheet("snooze_usd_01"), SessionState.Ringing(session))
            standard.onUpper(session, available, null, send)
            standard.onImUp()
            advanceUntilIdle()
            assertTrue(choices.calls.isEmpty())
        }

    @Test
    fun `a sheet that no longer belongs closes for good, but not while a payment is in flight`() =
        scope.runTest {
            open()
            host.follow(session.copy(paying = PurchaseIntentId("intent-1")), SnoozeAvailability.Unavailable(UnavailableReason.Offline))
            assertIs<SheetRequest.Confirm>(host.request.value)
            host.follow(session, available)
            assertIs<SheetRequest.Confirm>(host.request.value)
            host.follow(session, SnoozeAvailability.Unavailable(UnavailableReason.Offline))
            assertNull(host.request.value)
        }

    private class RecordingRunner : EffectRunner {
        val ran = mutableListOf<Any>()

        override suspend fun run(effect: SessionEffect) {
            ran += effect
        }

        override suspend fun apply(effect: EntryEffect) {
            ran += effect
        }
    }

    @Test
    fun `the effect runner shows the sheet's effects and still runs every effect in the runtime`() =
        scope.runTest {
            val runtime = RecordingRunner()
            val runner = SheetEffectRunner(runtime, host, logger) { SessionState.Ringing(session) }
            runner.run(SessionEffect.ShowSnoozeConfirm(offer))
            runner.apply(EntryEffect.WakeUiShown)
            assertIs<SheetRequest.Confirm>(host.request.value)
            assertEquals(listOf(SessionEffect.ShowSnoozeConfirm(offer), EntryEffect.WakeUiShown), ran(runtime))
        }

    @Test
    fun `other effects pass through to the runtime as the same instance and leave the sheet as it was`() =
        scope.runTest {
            open()
            val request = host.request.value
            val runtime = RecordingRunner()
            val runner = SheetEffectRunner(runtime, host, logger) { SessionState.Ringing(session) }
            val effects =
                listOf(
                    SessionEffect.StopSound,
                    SessionEffect.LaunchBilling(PurchaseIntentId("intent-1"), session.sessionId),
                    SessionEffect.RequestKeyguardDismiss(PurchaseIntentId("intent-1")),
                )
            effects.forEach { runner.run(it) }
            effects.zip(runtime.ran).forEach { (effect, ran) -> assertSame(effect, ran) }
            assertSame(request, host.request.value)
        }

    @Test
    fun `a sheet that throws on an effect never stops the runtime from running it`() =
        scope.runTest {
            val broken = hostOn(scope, ladder = FeeLadder { _, _ -> error("broken ladder") })
            val runtime = RecordingRunner()
            val runner = SheetEffectRunner(runtime, broken, logger) { SessionState.Ringing(session) }
            runner.run(SessionEffect.ShowSnoozeConfirm(offer))
            assertEquals(listOf<Any>(SessionEffect.ShowSnoozeConfirm(offer)), runtime.ran)
            assertTrue(logger.events.any { it.toString().contains("confirm sheet effect") })
        }

    private fun ran(runtime: RecordingRunner) = runtime.ran
}

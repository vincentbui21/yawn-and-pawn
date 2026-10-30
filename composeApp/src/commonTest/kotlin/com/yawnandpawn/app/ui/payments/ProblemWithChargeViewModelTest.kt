package com.yawnandpawn.app.ui.payments

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceSource
import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.billing.PurchaseRecordsRead
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.testing.DEFAULT_FAKE_INSTANT
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakePurchaseRecordRepository
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.aPurchaseRecord
import com.yawnandpawn.app.ui.links.AppLinks
import com.yawnandpawn.app.ui.links.ExternalLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Story 4.17: "Problem with a charge?": the order history link, the support picker and its email, and no app for either. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProblemWithChargeViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val records = FakePurchaseRecordRepository()
    private val zone = FakeTimeZoneProvider(TimeZone.UTC)
    private val links = RecordingLinks()
    private val logger = FakeLogger()
    private val appLinks =
        AppLinks(
            supportEmail = "help@example.com",
            orderHistoryUrl = "https://play.google.com/store/account/orderhistory",
            privacyUrl = "https://example.com/privacy/",
            termsUrl = "https://example.com/terms/",
            supportUrl = "https://example.com/support/",
            websiteUrl = "https://example.com/",
        )

    /** Writes the charge it was given into the body, so the test sees which charge the email is about. */
    private val writer = SupportEmailWriter { charge -> SupportEmail("Charge question", charge?.key.orEmpty()) }

    private val now = DEFAULT_FAKE_INSTANT

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.screen(): ProblemWithChargeViewModel = ProblemWithChargeViewModel(records, zone, links, appLinks, logger)

    @Test
    fun `Open Google Play order history opens the order history page`() =
        runTest(dispatcher) {
            val viewModel = screen()

            viewModel.onIntent(PaymentsIntent.OpenOrderHistoryClicked, writer)

            assertEquals(listOf("https://play.google.com/store/account/orderhistory"), links.urls)
            assertNull(viewModel.state.value.snackbar)
        }

    @Test
    fun `with no browser it says No browser found for 4 s`() =
        runTest(dispatcher) {
            links.canOpenUrls = false
            val viewModel = screen()

            viewModel.onIntent(PaymentsIntent.OpenOrderHistoryClicked, writer)
            assertEquals(ChargeSnackbar.NoBrowser, viewModel.state.value.snackbar)

            advanceTimeBy(ProblemWithChargeViewModel.SNACKBAR_MILLIS - 1)
            assertEquals(ChargeSnackbar.NoBrowser, viewModel.state.value.snackbar)
            advanceTimeBy(2)
            runCurrent()
            assertNull(viewModel.state.value.snackbar)
        }

    @Test
    fun `Email support lists the 10 most recent charges, newest first and picked`() =
        runTest(dispatcher) {
            repeat(12) { i -> records.putRecord(aPurchaseRecord(token = "t$i", purchasedAt = now + i.minutes)) }
            val viewModel = screen()

            viewModel.onIntent(PaymentsIntent.EmailSupportClicked, writer)

            val state = viewModel.state.value
            assertTrue(state.picking)
            assertEquals(10, state.charges.size)
            assertEquals((11 downTo 2).map { now + it.minutes }, state.charges.map { record(it.key).purchasedAt })
            assertEquals(state.charges.first(), state.selected)
            assertTrue(links.emails.isEmpty(), "nothing opens before the user confirms")
        }

    @Test
    fun `confirming writes to support about the picked charge, then the picker closes`() =
        runTest(dispatcher) {
            records.putRecord(aPurchaseRecord(token = "old", purchasedAt = now))
            records.putRecord(aPurchaseRecord(token = "new", purchasedAt = now + 9.minutes))
            val viewModel = screen()
            viewModel.onIntent(PaymentsIntent.EmailSupportClicked, writer)
            val older = viewModel.state.value.charges[1]

            viewModel.onIntent(PaymentsIntent.ChargePicked(older.key), writer)
            viewModel.onIntent(PaymentsIntent.SendEmailClicked, writer)

            assertEquals(listOf(SentEmail("help@example.com", "Charge question", older.key)), links.emails)
            assertFalse(viewModel.state.value.picking)
        }

    @Test
    fun `with no charge at all Email support opens the email straight away without order details`() =
        runTest(dispatcher) {
            val viewModel = screen()

            viewModel.onIntent(PaymentsIntent.EmailSupportClicked, writer)

            assertFalse(viewModel.state.value.picking)
            assertEquals(listOf(SentEmail("help@example.com", "Charge question", "")), links.emails)
        }

    @Test
    fun `a failing read is logged and the email still opens, without order details`() =
        runTest(dispatcher) {
            records.observeFailure = IllegalStateException("disk")
            val viewModel = screen()

            viewModel.onIntent(PaymentsIntent.EmailSupportClicked, writer)

            assertEquals(listOf(SentEmail("help@example.com", "Charge question", "")), links.emails)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("load charges for support", "disk")), logger.events)
        }

    @Test
    fun `with no email app the picker stays and the address note stays until the user moves on`() =
        runTest(dispatcher) {
            records.putRecord(aPurchaseRecord(purchasedAt = now))
            links.canSendEmail = false
            val viewModel = screen()
            viewModel.onIntent(PaymentsIntent.EmailSupportClicked, writer)

            viewModel.onIntent(PaymentsIntent.SendEmailClicked, writer)

            assertTrue(viewModel.state.value.picking)
            assertTrue(viewModel.state.value.noEmailApp)
            assertEquals("help@example.com", viewModel.state.value.supportEmail)
            // Not a 4 s snackbar: the address stays usable.
            advanceTimeBy(ProblemWithChargeViewModel.SNACKBAR_MILLIS * 10)
            assertTrue(viewModel.state.value.noEmailApp)

            viewModel.onIntent(PaymentsIntent.Back, writer)
            assertFalse(viewModel.state.value.noEmailApp)
        }

    @Test
    fun `Copy address puts the support address on the clipboard, confirmed only where the phone does not`() =
        runTest(dispatcher) {
            links.canSendEmail = false
            val viewModel = screen()
            viewModel.onIntent(PaymentsIntent.EmailSupportClicked, writer)
            assertTrue(viewModel.state.value.noEmailApp)

            viewModel.onIntent(PaymentsIntent.CopyAddressClicked, writer)
            assertEquals(listOf("help@example.com"), links.copied)
            assertEquals(ChargeSnackbar.AddressCopied, viewModel.state.value.snackbar)
            advanceTimeBy(ProblemWithChargeViewModel.SNACKBAR_MILLIS + 1)
            assertNull(viewModel.state.value.snackbar)

            links.phoneConfirmsCopy = true
            viewModel.onIntent(PaymentsIntent.CopyAddressClicked, writer)
            assertEquals(2, links.copied.size)
            assertNull(viewModel.state.value.snackbar)
        }

    @Test
    fun `before the charges are read Email support does nothing, so it never sends without the order details`() =
        runTest(dispatcher) {
            records.putRecord(aPurchaseRecord(purchasedAt = now))
            val notYetRead =
                object : PurchaseRecordRepository by records {
                    override fun observeAll(): Flow<PurchaseRecordsRead> = flow { awaitCancellation() }
                }
            val viewModel = ProblemWithChargeViewModel(notYetRead, zone, links, appLinks, logger)

            viewModel.onIntent(PaymentsIntent.EmailSupportClicked, writer)

            assertFalse(viewModel.state.value.loaded)
            assertFalse(viewModel.state.value.picking)
            assertTrue(links.emails.isEmpty())
        }

    @Test
    fun `the email's templates fill in the charge they are given, whatever the screen has loaded`() {
        val templates =
            SupportEmailTemplates(
                subject = "Charge question",
                orderIdLine = "Order ID: ${SupportEmailTemplates.SLOT}",
                dateLine = "Date: ${SupportEmailTemplates.SLOT}",
                priceLine = "Price: ${SupportEmailTemplates.SLOT}",
                statusLine = "Status: ${SupportEmailTemplates.SLOT}",
                snoozeStatus = "Snooze ${SupportEmailTemplates.SLOT}",
                notUsedStatus = "Not used, refunded automatically by Google",
            )
        // A charge on no list at all (and no price, so no money is formatted here).
        val charge = chargeOptionOf(aPurchaseRecord(orderId = "GPA.1", snoozeNumber = 3, priceSource = PriceSource.Tier), TimeZone.UTC)

        assertEquals(
            SupportEmail("Charge question", "Order ID: GPA.1\nDate: 2027-03-03T06:00:00+00:00\nStatus: Snooze 3"),
            templates.write(charge),
        )
        assertEquals(SupportEmail("Charge question", ""), templates.write(null))
    }

    @Test
    fun `Back closes the picker and nothing is sent`() =
        runTest(dispatcher) {
            records.putRecord(aPurchaseRecord(purchasedAt = now))
            val viewModel = screen()
            viewModel.onIntent(PaymentsIntent.EmailSupportClicked, writer)

            viewModel.onIntent(PaymentsIntent.Back, writer)

            assertFalse(viewModel.state.value.picking)
            assertTrue(links.emails.isEmpty())
        }

    @Test
    fun `a new charge shows at once, and a picked charge that is gone falls back to the newest`() =
        runTest(dispatcher) {
            records.putRecord(aPurchaseRecord(token = "a", purchasedAt = now))
            val viewModel = screen()
            viewModel.onIntent(PaymentsIntent.EmailSupportClicked, writer)
            viewModel.onIntent(PaymentsIntent.ChargePicked("not-a-charge"), writer)

            records.putRecord(aPurchaseRecord(token = "b", purchasedAt = now + 1.minutes))

            val state = viewModel.state.value
            assertEquals(2, state.charges.size)
            assertEquals(state.charges.first(), state.selected)
        }

    @Test
    fun `a charge keeps its amount only when it is what was charged, and words its status like Purchase history`() {
        val paid = chargeOptionOf(aPurchaseRecord(snoozeNumber = 2, price = Money.of(2, "USD")), TimeZone.UTC)
        val estimate = chargeOptionOf(aPurchaseRecord(priceSource = PriceSource.Tier), TimeZone.UTC)
        val stranded = chargeOptionOf(aPurchaseRecord(status = RecordStatus.Stranded, snoozeNumber = null), TimeZone.UTC)
        val reused = chargeOptionOf(aPurchaseRecord(status = RecordStatus.Reused, snoozeNumber = 3), TimeZone.UTC)

        assertEquals(Money.of(2, "USD"), paid.price)
        assertEquals(ChargeStatus.Snooze(2), paid.status)
        assertNull(estimate.price)
        assertEquals(ChargeStatus.NotUsed, stranded.status)
        assertEquals(ChargeStatus.Snooze(3), reused.status)
    }

    @Test
    fun `the purchase time is ISO 8601 with the zone's offset at that moment`() {
        val helsinki = TimeZone.of("Europe/Helsinki")
        // 2027-03-03 06:00 UTC is 08:00 in Helsinki (winter, +02:00); 2027-07-01 06:00 UTC is 09:00 (summer, +03:00).
        val winter = chargeOptionOf(aPurchaseRecord(purchasedAt = now), helsinki)
        val summer = chargeOptionOf(aPurchaseRecord(purchasedAt = kotlin.time.Instant.parse("2027-07-01T06:00:05Z")), helsinki)

        assertEquals(LocalDateTime(2027, 3, 3, 8, 0), winter.purchasedAt)
        assertEquals(UtcOffset(hours = 2), winter.offset)
        assertEquals("2027-03-03T08:00:00+02:00", winter.isoPurchaseTime())
        assertEquals("2027-07-01T09:00:05+03:00", summer.isoPurchaseTime())
        assertEquals("2027-03-03T06:00:00+00:00", chargeOptionOf(aPurchaseRecord(purchasedAt = now), TimeZone.UTC).isoPurchaseTime())
    }

    @Test
    fun `the email's facts carry the order id, time and status, and never the token, session, alarm or label`() {
        // An estimated price, so no money is formatted here (the phone's formatter); SupportEmailWriterTest covers the
        // price line with the app's strings.
        val record =
            aPurchaseRecord(
                token = "secret-token-value",
                sessionId = "secret-session-id",
                alarmId = "secret-alarm-id",
                snoozeNumber = 2,
                orderId = "GPA.3301-2345-6789-01234",
                priceSource = PriceSource.Snapshot,
            )

        val facts = supportEmailFacts(chargeOptionOf(record, TimeZone.UTC))

        assertEquals(
            listOf(
                SupportEmailFact.OrderId("GPA.3301-2345-6789-01234"),
                SupportEmailFact.Date("2027-03-03T06:00:00+00:00"),
                SupportEmailFact.Status(ChargeStatus.Snooze(2)),
            ),
            facts,
        )
        val text = facts.joinToString()
        listOf("secret-token-value", record.tokenHash, "secret-session-id", "secret-alarm-id").forEach { secret ->
            assertFalse(secret in text, "the email must not carry $secret")
        }
    }

    @Test
    fun `a charge with no order id, no known amount and no snooze number has only its date line`() {
        val record = aPurchaseRecord(orderId = null, priceSource = PriceSource.Unknown, snoozeNumber = null)

        val facts = supportEmailFacts(chargeOptionOf(record, TimeZone.UTC))

        assertEquals(listOf<SupportEmailFact>(SupportEmailFact.Date("2027-03-03T06:00:00+00:00")), facts)
    }

    private fun record(key: String) = records.records.single { it.tokenHash == key }

    private data class SentEmail(
        val to: String,
        val subject: String,
        val body: String,
    )

    private class RecordingLinks : ExternalLinks {
        var canOpenUrls = true
        var canSendEmail = true
        var phoneConfirmsCopy = false
        val urls = mutableListOf<String>()
        val emails = mutableListOf<SentEmail>()
        val copied = mutableListOf<String>()

        override fun copyText(text: String): Boolean {
            copied += text
            return !phoneConfirmsCopy
        }

        override fun openUrl(url: String): Boolean = canOpenUrls.also { if (it) urls += url }

        override fun sendEmail(
            to: String,
            subject: String,
            body: String,
        ): Boolean = canSendEmail.also { if (it) emails += SentEmail(to, subject, body) }
    }
}

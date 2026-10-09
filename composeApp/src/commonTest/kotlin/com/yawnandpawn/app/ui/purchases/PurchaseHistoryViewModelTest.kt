package com.yawnandpawn.app.ui.purchases

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceSource
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.billing.formatTotals
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.testing.DEFAULT_FAKE_INSTANT
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMoneyFormatter
import com.yawnandpawn.app.testing.FakePurchaseRecordRepository
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.aPurchaseRecord
import com.yawnandpawn.app.testing.aSessionHistoryRow
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Story 4.16: Purchase history's ordering, status mapping, amounts, alarm naming, month cards and failures. */
@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseHistoryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val records = FakePurchaseRecordRepository()
    private val alarms = FakeAlarmRepository()
    private val history = FakeSessionHistoryRepository()
    private val zone = FakeTimeZoneProvider(TimeZone.UTC)
    private val logger = FakeLogger()

    /** Wednesday 2027-03-03, 06:00 UTC. */
    private val now = DEFAULT_FAKE_INSTANT

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.screen(): PurchaseHistoryViewModel {
        val viewModel = PurchaseHistoryViewModel(records, alarms, history, zone, logger)
        backgroundScope.launch { viewModel.state.collect { } }
        return viewModel
    }

    private fun PurchaseHistoryViewModel.purchases(): List<Purchase> = state.value.purchases

    @Test
    fun `no records is the empty state, not loading and not a failure`() =
        runTest(dispatcher) {
            val state = screen().state.value

            assertEquals(PurchaseHistoryUiState(), state)
        }

    @Test
    fun `records are newest first and a new record shows at once`() =
        runTest(dispatcher) {
            records.putRecord(aPurchaseRecord(token = "old", snoozeNumber = 1, purchasedAt = now))
            records.putRecord(aPurchaseRecord(token = "new", snoozeNumber = 2, purchasedAt = now + 9.minutes))
            val viewModel = screen()
            assertEquals(listOf(2, 1), viewModel.purchases().map { it.snoozeNumber })

            records.putRecord(aPurchaseRecord(token = "newest", snoozeNumber = 3, purchasedAt = now + 18.minutes))

            assertEquals(listOf(3, 2, 1), viewModel.purchases().map { it.snoozeNumber })
        }

    @Test
    fun `granted, consumed and reused read as paid snoozes, stranded reads as not used instead of its number`() =
        runTest(dispatcher) {
            listOf(RecordStatus.Granted, RecordStatus.Consumed, RecordStatus.Reused, RecordStatus.Stranded).forEachIndexed { i, status ->
                records.putRecord(aPurchaseRecord(token = status.name, status = status, snoozeNumber = 2, purchasedAt = now - i.minutes))
            }

            val purchases = screen().purchases()

            assertEquals(listOf(false, false, false, true), purchases.map { it.stranded })
            assertEquals(listOf(2, 2, 2, null), purchases.map { it.snoozeNumber })
            assertTrue(purchases.all { it.price == Money.of(1, "USD") })
        }

    @Test
    fun `a stranded payment with no alarm or snooze number shows only its date and status`() =
        runTest(dispatcher) {
            records.putRecord(
                aPurchaseRecord(status = RecordStatus.Stranded, sessionId = null, alarmId = null, snoozeNumber = null, purchasedAt = now),
            )

            val purchase = screen().purchases().single()

            assertEquals(
                Purchase(LocalDate(2027, 3, 3), alarmTime = null, snoozeNumber = null, price = Money.of(1, "USD"), stranded = true),
                purchase,
            )
        }

    @Test
    fun `an amount shows only when it is what was charged, never an estimate`() =
        runTest(dispatcher) {
            PriceSource.entries.forEachIndexed { i, source ->
                records.putRecord(aPurchaseRecord(token = source.name, priceSource = source, purchasedAt = now - i.minutes))
            }

            val prices = screen().purchases().map { it.price }

            // In entry order: Intent, Snapshot, Tier, Unknown.
            assertEquals(listOf(Money.of(1, "USD"), null, null, null), prices)
        }

    @Test
    fun `the alarm is its label, else the time its session rang for, else its own time, else nothing`() =
        runTest(dispatcher) {
            alarms.upsert(anAlarm(id = "gym", time = LocalTime(6, 45), label = "Gym", requestCode = 1001))
            alarms.upsert(anAlarm(id = "plain", time = LocalTime(7, 30), requestCode = 1002))
            alarms.upsert(anAlarm(id = "blank", time = LocalTime(8, 0), label = "  ", requestCode = 1003))
            // The plain alarm's session rang at 7:00, before the alarm was moved to 7:30.
            history.upsert(aSessionHistoryRow(sessionId = "s-plain", alarmId = "plain").copy(scheduledAt = now + 1.hours))
            history.upsert(aSessionHistoryRow(sessionId = "s-deleted", alarmId = "deleted").copy(scheduledAt = now + 2.hours))
            records.putRecord(aPurchaseRecord(token = "a", sessionId = "s-gym", alarmId = "gym", purchasedAt = now + 5.minutes))
            records.putRecord(aPurchaseRecord(token = "b", sessionId = "s-plain", alarmId = "plain", purchasedAt = now + 4.minutes))
            records.putRecord(aPurchaseRecord(token = "c", sessionId = "s-none", alarmId = "plain", purchasedAt = now + 3.minutes))
            records.putRecord(aPurchaseRecord(token = "d", sessionId = "s-deleted", alarmId = "deleted", purchasedAt = now + 2.minutes))
            records.putRecord(aPurchaseRecord(token = "e", sessionId = "s-gone", alarmId = "gone", purchasedAt = now + 1.minutes))
            records.putRecord(aPurchaseRecord(token = "f", sessionId = "s-blank", alarmId = "blank", purchasedAt = now))

            val purchases = screen().purchases()

            assertEquals(listOf("Gym", null, null, null, null, null), purchases.map { it.alarmLabel })
            assertEquals(
                listOf(LocalTime(6, 45), LocalTime(7, 0), LocalTime(7, 30), LocalTime(8, 0), null, LocalTime(8, 0)),
                purchases.map { it.alarmTime },
            )
        }

    @Test
    fun `the date and the session time are in the phone's zone`() =
        runTest(dispatcher) {
            zone.set(TimeZone.of("Europe/Helsinki"))
            history.upsert(aSessionHistoryRow(sessionId = "s", alarmId = "a").copy(scheduledAt = now + 16.hours))
            records.putRecord(aPurchaseRecord(sessionId = "s", alarmId = "a", purchasedAt = now + 16.hours + 5.minutes))

            val purchase = screen().purchases().single()

            // 22:05 UTC is 00:05 the next day in Helsinki (UTC+2 in March).
            assertEquals(LocalDate(2027, 3, 4), purchase.date)
            assertEquals(LocalTime(0, 0), purchase.alarmTime)
        }

    @Test
    fun `a failing read of the records is the load failure, never the empty state, and Try again reads again`() =
        runTest(dispatcher) {
            records.putRecord(aPurchaseRecord())
            records.observeFailure = IllegalStateException("disk I/O error")
            val viewModel = screen()

            assertEquals(PurchaseHistoryUiState(loadFailed = true), viewModel.state.value)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("load purchase history", "disk I/O error")), logger.events)

            records.observeFailure = null
            viewModel.onIntent(PurchaseHistoryIntent.RetryLoad)

            assertFalse(viewModel.state.value.loadFailed)
            assertEquals(1, viewModel.purchases().size)
        }

    @Test
    fun `a failing read of the alarms or a session only drops the alarm's name, and is logged`() =
        runTest(dispatcher) {
            alarms.upsert(anAlarm(id = "gym", time = LocalTime(6, 45), label = "Gym", requestCode = 1001))
            alarms.failure = DomainError.StorageFailure("alarms gone")
            history.findFailure = DomainError.StorageFailure("history gone")
            records.putRecord(aPurchaseRecord(sessionId = "s", alarmId = "gym"))

            val state = screen().state.value

            assertFalse(state.loadFailed)
            assertEquals(
                Purchase(LocalDate(2027, 3, 3), alarmTime = null, snoozeNumber = 1, price = Money.of(1, "USD")),
                state.purchases.single(),
            )
            assertEquals(
                listOf("load alarm labels", "load purchase session"),
                logger.events.map { (it as LogEvent.OperationFailed).operation },
            )
        }

    @Test
    fun `month cards are newest first, and each month's total counts only used payments with a known amount, per currency`() {
        val sep = { day: Int -> LocalDate(2026, 9, day) }
        val purchases =
            listOf(
                Purchase(sep(23), LocalTime(7, 30), 2, Money.of(2, "USD")),
                Purchase(sep(22), LocalTime(7, 30), 1, Money(1_190_000, "EUR")),
                Purchase(sep(21), LocalTime(7, 30), 1, null),
                Purchase(sep(20), LocalTime(7, 30), null, Money.of(5, "USD"), stranded = true),
                Purchase(sep(2), LocalTime(7, 30), 1, Money.of(1, "USD")),
                Purchase(LocalDate(2026, 8, 12), LocalTime(5, 45), null, Money.of(1, "USD"), stranded = true),
            )

        val months = monthsOf(purchases)

        assertEquals(listOf(LocalDate(2026, 9, 1), LocalDate(2026, 8, 1)), months.map { it.month })
        assertEquals(purchases.take(5), months[0].purchases)
        assertEquals("USD 3.000000 + EUR 1.190000", FakeMoneyFormatter().formatTotals(months[0].paid))
        assertEquals(emptyList(), months[1].paid)
        assertTrue(monthsOf(emptyList()).isEmpty())
    }
}

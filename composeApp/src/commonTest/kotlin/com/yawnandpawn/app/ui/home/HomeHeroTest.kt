package com.yawnandpawn.app.ui.home

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceSource
import com.yawnandpawn.app.core.billing.PurchaseRecordsRead
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.history.MissedNotes
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.stats.DayTicker
import com.yawnandpawn.app.core.stats.ObserveProgress
import com.yawnandpawn.app.core.stats.progressSnapshot
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeCheckConfigRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeIdGenerator
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMissedNoteDismissals
import com.yawnandpawn.app.testing.FakePurchaseRecordRepository
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeTimeChangeSignal
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.aPurchaseRecord
import com.yawnandpawn.app.testing.aSessionHistoryRow
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.testing.anEvaluateReliability
import com.yawnandpawn.app.testing.noReRegisterSuggestions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Story 6.3: `card-hero` on Home from 6.2's ObserveProgress, over the `:testing` fakes: shown with an alarm or a history
 * row, hidden while loading or after a failed read, and updated by a new session, a purchase record, a new day or week
 * (with a tick), a zone change and a resume.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeHeroTest {
    private val dispatcher = UnconfinedTestDispatcher()

    /** Wednesday 2027-03-03, 06:00 UTC. */
    private val clock = FakeClock(Instant.parse("2027-03-03T06:00:00Z"))
    private val zone = FakeTimeZoneProvider(TimeZone.UTC)
    private val signal = FakeTimeChangeSignal()
    private val logger = FakeLogger()
    private val history = FakeSessionHistoryRepository()
    private val purchases = FakePurchaseRecordRepository()
    private val alarms = FakeAlarmRepository()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun progress(sessions: SessionHistoryRepository = history) =
        ObserveProgress(sessions, purchases, DayTicker(clock, zone, signal), EmptyCoroutineContext)

    private fun viewModel(progress: ObserveProgress? = progress()): HomeViewModel {
        val fixture = AlarmUseCasesFixture(repository = alarms, clock = clock, timeZoneProvider = zone)
        val checkConfigs = FakeCheckConfigRepository(alarms)
        return HomeViewModel(
            checkConfigs,
            AlarmActions(fixture.setEnabled, fixture.delete, logger),
            clock,
            zone,
            signal,
            // The missed note reads its own history, so a Missed row here never adds a note to these tests.
            MissedNotes(FakeSessionHistoryRepository(), FakeMissedNoteDismissals()),
            anEvaluateReliability(checks = checkConfigs),
            noReRegisterSuggestions(clock),
            evaluationContext = dispatcher,
            progress = progress,
        )
    }

    private fun TestScope.home(progress: ObserveProgress? = progress()): HomeViewModel {
        val viewModel = viewModel(progress)
        backgroundScope.launch { viewModel.state.collect { } }
        return viewModel
    }

    private suspend fun anAlarmStored() {
        alarms.upsert(anAlarm(id = FakeIdGenerator.fakeUuid(1), time = LocalTime(7, 0)))
    }

    /** A finished session of [day] at 06:00 UTC with [outcome]. */
    private suspend fun session(
        id: String,
        day: LocalDate,
        outcome: SessionOutcome = SessionOutcome.OnTime,
    ) {
        val at = Instant.parse("${day}T06:00:00Z")
        history.upsert(sessionRow(id, at, outcome))
    }

    private fun sessionRow(
        id: String,
        at: Instant,
        outcome: SessionOutcome,
    ): SessionHistoryRow =
        aSessionHistoryRow(sessionId = id).copy(
            scheduledAt = at,
            firstRingAt = at,
            endedAt = at + 3.minutes,
            snoozeCount = if (outcome == SessionOutcome.Snoozed) 1 else 0,
            outcome = outcome,
        )

    private suspend fun paid(
        token: String,
        price: Money,
        at: Instant = Instant.parse("2027-03-03T05:40:00Z"),
        status: RecordStatus = RecordStatus.Consumed,
        source: PriceSource = PriceSource.Intent,
    ) {
        purchases.putRecord(aPurchaseRecord(token = token, status = status, price = price, priceSource = source, purchasedAt = at))
    }

    private val HomeViewModel.hero: HomeHero? get() = state.value.hero

    // The I/O matrix --------------------------------------------------------------------------------------------------

    @Test
    fun `fresh install shows no hero, only the empty state`() =
        runTest(dispatcher) {
            val viewModel = home()

            assertEquals(HomeUiState(), viewModel.state.value)
        }

    @Test
    fun `the first alarm shows a 0 streak and nothing paid`() =
        runTest(dispatcher) {
            anAlarmStored()

            assertEquals(HomeHero(streakDays = 0, paidThisWeek = emptyList()), home().hero)
        }

    @Test
    fun `history without alarms shows the hero over the empty state, for any outcome`() =
        runTest(dispatcher) {
            session("s1", LocalDate(2027, 3, 1), SessionOutcome.Test)
            val viewModel = home()

            assertTrue(
                viewModel.state.value.alarms
                    .isEmpty(),
            )
            assertEquals(HomeHero(0), viewModel.hero)
        }

    @Test
    fun `one good day is a streak of 1`() =
        runTest(dispatcher) {
            session("s1", LocalDate(2027, 3, 3))

            assertEquals(HomeHero(1), home().hero)
        }

    @Test
    fun `money in one currency this ISO week is its total`() =
        runTest(dispatcher) {
            anAlarmStored()
            paid("t1", Money(3_000_000, "EUR"))

            assertEquals(listOf(Money(3_000_000, "EUR")), home().hero?.paidThisWeek)
        }

    @Test
    fun `money in two currencies is one total each, in currency-code order`() =
        runTest(dispatcher) {
            anAlarmStored()
            paid("t1", Money(1_000_000, "USD"))
            paid("t2", Money(2_990_000, "EUR"))
            paid("t3", Money(5_990_000, "EUR"))

            assertEquals(listOf(Money(8_980_000, "EUR"), Money(1_000_000, "USD")), home().hero?.paidThisWeek)
        }

    @Test
    fun `a stranded payment, an estimated price and last week's payment are not counted`() =
        runTest(dispatcher) {
            anAlarmStored()
            paid("stranded", Money(1_000_000, "USD"), status = RecordStatus.Stranded)
            paid("estimate", Money(1_000_000, "USD"), source = PriceSource.Snapshot)
            paid("tier", Money(1_000_000, "USD"), source = PriceSource.Tier)
            // Sunday 2027-02-28 is in the ISO week before Wednesday 2027-03-03.
            paid("last-week", Money(1_000_000, "USD"), at = Instant.parse("2027-02-28T07:00:00Z"))

            assertEquals(HomeHero(0, emptyList()), home().hero)
        }

    @Test
    fun `a session recorded while Home shows updates the streak`() =
        runTest(dispatcher) {
            anAlarmStored()
            val viewModel = home()
            assertEquals(0, viewModel.hero?.streakDays)

            session("s1", LocalDate(2027, 3, 2))
            session("s2", LocalDate(2027, 3, 3))

            assertEquals(2, viewModel.hero?.streakDays)

            session("s3", LocalDate(2027, 3, 3), SessionOutcome.Snoozed)

            assertEquals(0, viewModel.hero?.streakDays)
        }

    @Test
    fun `a purchase record put while Home shows updates the money line`() =
        runTest(dispatcher) {
            anAlarmStored()
            val viewModel = home()
            assertEquals(emptyList(), viewModel.hero?.paidThisWeek)

            paid("t1", Money(2_990_000, "EUR"))

            assertEquals(listOf(Money(2_990_000, "EUR")), viewModel.hero?.paidThisWeek)
        }

    @Test
    fun `a new week resets the money on the next tick`() =
        runTest(dispatcher) {
            anAlarmStored()
            clock.set(Instant.parse("2027-03-07T23:59:00Z"))
            paid("t1", Money(2_990_000, "EUR"), at = Instant.parse("2027-03-07T07:00:00Z"))
            val viewModel = home()
            assertEquals(listOf(Money(2_990_000, "EUR")), viewModel.hero?.paidThisWeek)

            clock.set(Instant.parse("2027-03-08T00:00:30Z"))
            assertEquals(listOf(Money(2_990_000, "EUR")), viewModel.hero?.paidThisWeek, "nothing recomputes before the tick")
            signal.emit()

            assertEquals(emptyList(), viewModel.hero?.paidThisWeek)
        }

    @Test
    fun `a new local date is picked up when Home resumes, with no tick`() =
        runTest(dispatcher) {
            anAlarmStored()
            clock.set(Instant.parse("2027-03-07T23:00:00Z"))
            paid("t1", Money(2_990_000, "EUR"), at = Instant.parse("2027-03-07T07:00:00Z"))
            val viewModel = home()
            assertEquals(listOf(Money(2_990_000, "EUR")), viewModel.hero?.paidThisWeek)

            clock.set(Instant.parse("2027-03-08T07:00:00Z"))
            viewModel.onIntent(HomeIntent.Resumed)

            assertEquals(emptyList(), viewModel.hero?.paidThisWeek)
        }

    @Test
    fun `a zone change recomputes the day and the week on the next tick`() =
        runTest(dispatcher) {
            anAlarmStored()
            // Sunday 23:30 in UTC, but already Monday 00:30 in Berlin.
            clock.set(Instant.parse("2027-03-07T23:30:00Z"))
            paid("t1", Money(2_990_000, "EUR"), at = Instant.parse("2027-03-07T22:00:00Z"))
            val viewModel = home()
            assertEquals(listOf(Money(2_990_000, "EUR")), viewModel.hero?.paidThisWeek)

            zone.set(TimeZone.of("Europe/Berlin"))
            signal.emit()

            assertEquals(emptyList(), viewModel.hero?.paidThisWeek)
        }

    @Test
    fun `while the stats load there is no hero, never a guessed 0`() =
        runTest(dispatcher) {
            anAlarmStored()
            val neverEmits =
                object : SessionHistoryRepository by FakeSessionHistoryRepository() {
                    override fun observeAll(): Flow<List<SessionHistoryRow>> = flow { awaitCancellation() }
                }
            val viewModel = home(progress(neverEmits))

            assertEquals(1, viewModel.state.value.alarms.size)
            assertNull(viewModel.hero)
        }

    @Test
    fun `a failed read is logged and shows no hero, then is read again after 1 s, 2 s`() =
        runTest(dispatcher) {
            anAlarmStored()
            session("s1", LocalDate(2027, 3, 3))
            history.observeFailure = IllegalStateException("closed")
            val viewModel = home()
            assertNull(viewModel.hero)
            assertEquals(1, viewModel.state.value.alarms.size, "Home still lists the alarms")
            advanceTimeBy(1_500) // the second read (after 1 s) fails too
            assertNull(viewModel.hero)

            history.observeFailure = null
            advanceTimeBy(2_000) // the third read, 2 s later, works

            assertEquals(HomeHero(1), viewModel.hero)
            assertEquals(List(2) { LogEvent.OperationFailed("load progress", "closed") }, logger.events)
        }

    @Test
    fun `a hero already shown goes on a failed read, logged, and comes back on the retry`() =
        runTest(dispatcher) {
            anAlarmStored()
            session("s1", LocalDate(2027, 3, 3))
            val viewModel = home()
            assertEquals(HomeHero(1), viewModel.hero)

            history.observeFailure = IllegalStateException("closed")
            session("s2", LocalDate(2027, 3, 2))
            assertNull(viewModel.hero, "never the stale streak after a failed read")

            history.observeFailure = null
            advanceTimeBy(1_001)

            assertEquals(HomeHero(2), viewModel.hero)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("load progress", "closed")), logger.events)
        }

    @Test
    fun `coming back to Home keeps the last hero until the new snapshot, with no flicker`() =
        runTest(dispatcher) {
            anAlarmStored()
            session("s1", LocalDate(2027, 3, 3))
            var reads = 0
            // The second read never answers, like a slow database when Home comes back.
            val slowSecondRead =
                object : SessionHistoryRepository by history {
                    override fun observeAll(): Flow<List<SessionHistoryRow>> =
                        flow { if (reads++ == 0) emitAll(history.observeAll()) else awaitCancellation() }
                }
            val viewModel = viewModel(progress(slowSecondRead))
            val first = launch { viewModel.state.collect { } }
            assertEquals(HomeHero(1), viewModel.hero)
            first.cancel()
            advanceTimeBy(6_000) // Home was away long enough for the reads to stop

            backgroundScope.launch { viewModel.state.collect { } }

            assertEquals(2, reads)
            assertEquals(HomeHero(1), viewModel.hero)
        }

    @Test
    fun `alarms that failed to load show no hero, even with history`() =
        runTest(dispatcher) {
            anAlarmStored()
            session("s1", LocalDate(2027, 3, 3))
            alarms.failure = DomainError.StorageFailure("closed")
            val viewModel = home()

            assertTrue(viewModel.state.value.loadFailed)
            assertNull(viewModel.hero)
        }

    @Test
    fun `with no stats bound there is no hero`() =
        runTest(dispatcher) {
            anAlarmStored()
            session("s1", LocalDate(2027, 3, 3))

            assertNull(home(progress = null).hero)
        }

    @Test
    fun `the time signal is only listened to while Home is shown, with the stats bound`() =
        runTest(dispatcher) {
            assertEquals(0, signal.subscribers)
            val viewModel = viewModel()
            assertEquals(0, signal.subscribers, "nothing listens before Home is shown")

            val collector = launch { viewModel.state.collect { } }
            // The countdown's ticks and the stats' DayTicker each listen.
            assertEquals(2, signal.subscribers)

            collector.cancel()
            advanceTimeBy(5_001)

            assertEquals(0, signal.subscribers)
        }

    // withHero, the pure gate ------------------------------------------------------------------------------------------

    private val today = LocalDate(2027, 3, 3)

    private fun snapshot(rows: List<SessionHistoryRow> = emptyList()) =
        progressSnapshot(rows, PurchaseRecordsRead(emptyList()), today, TimeZone.UTC)

    private val card = AlarmCard("1", LocalTime(7, 0), emptySet(), null, emptyList(), enabled = true)
    private val goodDay = sessionRow("s1", Instant.parse("2027-03-03T06:00:00Z"), SessionOutcome.OnTime)

    @Test
    fun `withHero shows the hero only with stats, alarms or history, and the alarms read`() {
        val withAlarm = HomeUiState(alarms = listOf(card))
        val cases =
            listOf(
                Triple("no stats yet", withAlarm to null, null),
                Triple("no alarms, no history", HomeUiState() to snapshot(), null),
                Triple("an alarm, no history", withAlarm to snapshot(), HomeHero(0)),
                Triple("history, no alarms", HomeUiState() to snapshot(listOf(goodDay)), HomeHero(1)),
                Triple("alarms loading", HomeUiState(isLoading = true) to snapshot(listOf(goodDay)), null),
                Triple("alarms failed", HomeUiState(loadFailed = true) to snapshot(listOf(goodDay)), null),
            )
        cases.forEach { (name, input, expected) ->
            val (state, stats) = input
            assertEquals(expected, state.withHero(stats).hero, name)
        }
    }
}

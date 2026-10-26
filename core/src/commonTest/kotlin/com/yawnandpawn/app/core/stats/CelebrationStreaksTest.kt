package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.EngineLogger
import com.yawnandpawn.app.core.session.InMemoryHistory
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.ringSession
import com.yawnandpawn.app.core.session.testConfig
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Story 6.4: the in-memory snapshot behind the celebration, read once per session and decided once per session. */
class CelebrationStreaksTest {
    private val history = InMemoryHistory()
    private val logger = EngineLogger()

    /** 07:05 in Berlin on 3 March 2027, five minutes after [ringSession]'s 06:00 UTC alarm. */
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.parse("2027-03-03T06:05:00Z")
        }
    private val celebrations = CelebrationStreaks(history, clock, TimeZoneProvider { StatsZone }, logger)

    private val session: SessionData = ringSession()
    private val today = LocalDate(2027, 3, 3)

    private fun failures(): List<LogEvent.OperationFailed> =
        logger.events.filterIsInstance<LogEvent.OperationFailed>().filter { it.operation == "load celebration streak" }

    /** [days] good days ending yesterday, in [history]. */
    private fun goodDays(days: Int) = (1..days).forEach { aSession(today.day(-it)).let { row -> history.rows[row.sessionId] = row } }

    @Test
    fun `a loaded snapshot gives the streak before and after this session`() =
        runTest {
            goodDays(11)

            celebrations.prepare(session.sessionId)

            assertEquals(SuccessStreak.Known(before = 11, after = 12), celebrations.streakFor(session))
            assertEquals(emptyList(), failures())
        }

    @Test
    fun `prepare twice for one session reads once`() =
        runTest {
            celebrations.prepare(session.sessionId)
            celebrations.prepare(session.sessionId)

            assertEquals(1, history.allCalls)
        }

    @Test
    fun `a new session reads again and drops the snapshot before it`() =
        runTest {
            goodDays(2)
            celebrations.prepare("an-earlier-session")
            celebrations.prepare(session.sessionId)

            assertEquals(2, history.allCalls)
            assertEquals(SuccessStreak.Unknown, celebrations.streakFor(session.copy(sessionId = "an-earlier-session")))
            assertEquals(SuccessStreak.Known(before = 2, after = 3), celebrations.streakFor(session))
        }

    @Test
    fun `while the read is in flight it is Pending, logged once, and the later load does not change it`() =
        runTest {
            goodDays(3)
            val gate = CompletableDeferred<Unit>()
            history.allGate = gate
            launch { celebrations.prepare(session.sessionId) }
            runCurrent()
            assertEquals(1, history.allCalls)

            assertEquals(SuccessStreak.Pending, celebrations.streakFor(session))
            gate.complete(Unit)
            runCurrent()

            assertEquals(SuccessStreak.Pending, celebrations.streakFor(session), "decided once")
            assertEquals(listOf(LogEvent.OperationFailed("load celebration streak", "still loading")), failures())
        }

    @Test
    fun `a failed read is Unknown and logged once with its diagnostic`() =
        runTest {
            history.allFailure = DomainError.StorageFailure("disk gone")
            celebrations.prepare(session.sessionId)

            assertEquals(SuccessStreak.Unknown, celebrations.streakFor(session))
            assertEquals(SuccessStreak.Unknown, celebrations.streakFor(session))

            val logged = failures().single()
            assertTrue("disk gone" in logged.cause, logged.cause)
        }

    @Test
    fun `a read that throws is a failure too, never a stuck load`() =
        runTest {
            history.allGate = CompletableDeferred<Unit>().apply { completeExceptionally(IllegalStateException("broken row")) }
            celebrations.prepare(session.sessionId)

            assertEquals(SuccessStreak.Unknown, celebrations.streakFor(session))
            assertEquals(listOf(LogEvent.OperationFailed("load celebration streak", "broken row")), failures())
        }

    @Test
    fun `requested but not read yet is Pending, logged as not started, and the read still happens once`() =
        runTest {
            goodDays(2)
            celebrations.request(session.sessionId)
            celebrations.request(session.sessionId)

            assertEquals(SuccessStreak.Pending, celebrations.streakFor(session))
            assertEquals(listOf(LogEvent.OperationFailed("load celebration streak", "not started")), failures())

            celebrations.prepare(session.sessionId)
            celebrations.prepare(session.sessionId)
            assertEquals(1, history.allCalls)
            assertEquals(SuccessStreak.Pending, celebrations.streakFor(session), "decided once")
        }

    @Test
    fun `a request for a new session drops the snapshot before it, a late request keeps a loaded one`() =
        runTest {
            goodDays(1)
            celebrations.prepare("an-earlier-session")
            celebrations.request(session.sessionId)
            assertEquals(SuccessStreak.Unknown, celebrations.streakFor(session.copy(sessionId = "an-earlier-session")))

            celebrations.prepare(session.sessionId)
            celebrations.request(session.sessionId)

            assertEquals(2, history.allCalls)
            assertEquals(SuccessStreak.Known(before = 1, after = 2), celebrations.streakFor(session))
        }

    @Test
    fun `a failure while deciding is Unknown and logged, never thrown`() =
        runTest {
            val broken = CelebrationStreaks(history, clock, { error("no zone") }, logger)
            broken.prepare(session.sessionId)

            assertEquals(SuccessStreak.Unknown, broken.streakFor(session))
            assertEquals(SuccessStreak.Unknown, broken.streakFor(session))
            assertEquals(listOf(LogEvent.OperationFailed("load celebration streak", "no zone")), failures())
        }

    @Test
    fun `never requested in this process is Unknown, logged once as not loaded`() {
        assertEquals(SuccessStreak.Unknown, celebrations.streakFor(session))
        assertEquals(SuccessStreak.Unknown, celebrations.streakFor(session))

        assertEquals(listOf(LogEvent.OperationFailed("load celebration streak", "not loaded")), failures())
        assertEquals(0, history.allCalls)
    }

    @Test
    fun `the decision is kept, a load after it does not change it`() =
        runTest {
            goodDays(5)
            assertEquals(SuccessStreak.Unknown, celebrations.streakFor(session))

            celebrations.prepare(session.sessionId)

            assertEquals(SuccessStreak.Unknown, celebrations.streakFor(session))
            assertEquals(1, failures().size)
        }

    @Test
    fun `a new session is decided on its own`() =
        runTest {
            goodDays(1)
            celebrations.prepare(session.sessionId)
            assertEquals(SuccessStreak.Known(before = 1, after = 2), celebrations.streakFor(session))

            // The next morning's session: this one's row is now in history.
            history.rows[session.sessionId] =
                aSession(today).copy(sessionId = session.sessionId, outcome = SessionOutcome.OnTime)
            val next = session.copy(sessionId = "session-2")
            celebrations.prepare(next.sessionId)

            assertEquals(SuccessStreak.Known(before = 2, after = 2), celebrations.streakFor(next), "same day, already good")
        }

    @Test
    fun `test and snoozed sessions have no streak, no read and no log`() =
        runTest {
            celebrations.prepare(session.sessionId)
            val test = session.copy(config = testConfig(testMode = true))
            val snoozed = session.copy(snoozesGranted = 1)

            assertEquals(SuccessStreak.NONE, celebrations.streakFor(test))
            assertEquals(SuccessStreak.NONE, celebrations.streakFor(snoozed))
            assertEquals(emptyList(), failures())
            // Neither was kept: the real decision for this session is still open.
            assertEquals(SuccessStreak.Known(before = 0, after = 1), celebrations.streakFor(session))
        }
}

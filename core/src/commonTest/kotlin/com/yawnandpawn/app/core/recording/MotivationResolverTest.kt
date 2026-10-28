package com.yawnandpawn.app.core.recording

import com.yawnandpawn.app.core.alarm.Motivation
import com.yawnandpawn.app.core.alarm.RecordingLogger
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.SessionJson
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.core.session.ringSession
import com.yawnandpawn.app.core.session.testConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Story 7.3: the motivation a session freezes at its start, and the one its Success plays. */
class MotivationResolverTest {
    private val logger = RecordingLogger()

    private fun recording(
        id: String,
        day: Int,
        missing: Boolean = false,
    ) = Recording(id, "Message $day", 5_000, Recording.fileNameFor(id), Instant.parse("2027-03-01T06:00:00Z") + day.days, missing)

    private val three = listOf(recording("c", day = 3), recording("a", day = 1), recording("b", day = 2))

    private fun pick(
        sessionId: String,
        recordings: List<Recording> = three,
    ): String = assertIs<Motivation.Recording>(MotivationResolver.resolve(Motivation.Random, sessionId, recordings, false)).id

    @Test
    fun `Random picks one recording from the session seed, the same one for the same session whatever the list order`() {
        val first = pick("session-1")

        assertTrue(first in setOf("a", "b", "c"))
        repeat(3) { assertEquals(first, pick("session-1")) }
        assertEquals(first, pick("session-1", three.reversed()), "ordered oldest first before the pick")
        assertEquals(first, pick("session-1", three.shuffled()))
    }

    @Test
    fun `Random spreads over every recording across sessions`() {
        val picks = (1..300).map { pick("session-$it") }.groupingBy { it }.eachCount()

        assertEquals(setOf("a", "b", "c"), picks.keys)
        picks.values.forEach { assertTrue(it in 60..140, "picks $picks") }
    }

    @Test
    fun `recordings created at the same time are ordered by id, so the pick does not depend on the list order`() {
        val same = listOf(recording("y", day = 1), recording("x", day = 1), recording("z", day = 1))

        val picks = (1..20).map { pick("session-$it", same) }

        assertEquals(picks, (1..20).map { pick("session-$it", same.reversed()) })
    }

    @Test
    fun `a missing recording is never picked, and with none usable Random is None`() {
        val withMissing = listOf(recording("a", day = 1, missing = true), recording("b", day = 2), recording("c", day = 3, missing = true))

        (1..50).forEach { assertEquals("b", pick("session-$it", withMissing)) }
        val allMissing = withMissing.map { it.copy(missing = true) }
        assertEquals(Motivation.None, MotivationResolver.resolve(Motivation.Random, "s", allMissing, false))
        assertEquals(Motivation.None, MotivationResolver.resolve(Motivation.Random, "s", emptyList(), false))
    }

    @Test
    fun `a message and None stay as they are`() {
        assertEquals(Motivation.Recording("gone"), MotivationResolver.resolve(Motivation.Recording("gone"), "s", three, false))
        assertEquals(Motivation.None, MotivationResolver.resolve(Motivation.None, "s", three, false))
    }

    @Test
    fun `before the first unlock every motivation is None`() {
        listOf(Motivation.None, Motivation.Random, Motivation.Recording("a")).forEach {
            assertEquals(Motivation.None, MotivationResolver.resolve(it, "s", three, beforeFirstUnlock = true))
        }
    }

    @Test
    fun `freeze resolves Random from the repository and leaves the rest of the config as it was`() =
        runTest {
            val config = testConfig().copy(motivation = Motivation.Random)

            val frozen = MotivationResolver.freeze(config, "session-1", false, Listed(three), logger)

            assertEquals(config.copy(motivation = Motivation.Recording(pick("session-1"))), frozen)
            assertTrue(logger.events.isEmpty())
        }

    @Test
    fun `freeze reads nothing for a message, None, or before the first unlock`() =
        runTest {
            val repository = Listed(three)
            val message = testConfig().copy(motivation = Motivation.Recording("a"))

            assertSame(message, MotivationResolver.freeze(message, "s", false, repository, logger))
            val none = testConfig()
            assertSame(none, MotivationResolver.freeze(none, "s", false, repository, logger))
            assertEquals(none, MotivationResolver.freeze(message, "s", true, repository, logger))
            assertEquals(none, MotivationResolver.freeze(message.copy(motivation = Motivation.Random), "s", true, repository, logger))
            assertEquals(0, repository.reads)
        }

    @Test
    fun `a failing read freezes None and logs only the error type`() =
        runTest {
            val config = testConfig().copy(motivation = Motivation.Random)

            val frozen = MotivationResolver.freeze(config, "s", false, Failing(), logger)

            assertEquals(Motivation.None, frozen.motivation)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed(MotivationResolver.OPERATION, "IllegalStateException")), logger.events)
        }

    @Test
    fun `a cancellation thrown by the repository while the fire goes on freezes None, so the alarm still rings`() =
        runTest {
            val config = testConfig().copy(motivation = Motivation.Random)

            val frozen = MotivationResolver.freeze(config, "s", false, Failing(CancellationException("closed")), logger)

            assertEquals(Motivation.None, frozen.motivation)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed(MotivationResolver.OPERATION, "CancellationException")), logger.events)
        }

    @Test
    fun `a read slower than 500 ms freezes None, logged, and never waits longer`() =
        runTest {
            val config = testConfig().copy(motivation = Motivation.Random)

            val frozen = MotivationResolver.freeze(config, "s", false, Listed(three, never = true), logger)

            assertEquals(Motivation.None, frozen.motivation)
            assertEquals(MotivationResolver.READ_TIMEOUT.inWholeMilliseconds, currentTime)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed(MotivationResolver.OPERATION, "timed out")), logger.events)
        }

    @Test
    fun `the frozen message survives the stored session, so a restored session plays the same one`() =
        runTest {
            val random = testConfig().copy(motivation = Motivation.Random)
            val frozen = MotivationResolver.freeze(random, "session-1", false, Listed(three), logger)
            val completed = SessionState.Completed(ringSession(frozen))

            val restored = assertIs<StoredSession.Found>(SessionJson.decode(SessionJson.encode(completed))).state

            val session = assertIs<SessionState.Completed>(restored).session
            assertEquals(pick("session-1"), MotivationResolver.playableRecordingId(session))
        }

    @Test
    fun `Success plays only a frozen message, never for a session that started before the first unlock`() {
        val session = ringSession(testConfig().copy(motivation = Motivation.Recording("a")))

        assertEquals("a", MotivationResolver.playableRecordingId(session))
        assertNull(MotivationResolver.playableRecordingId(session.copy(startedBeforeUnlock = true)))
        val unresolved = ringSession(testConfig().copy(motivation = Motivation.Random))
        assertNull(MotivationResolver.playableRecordingId(unresolved), "stored unresolved")
        assertNull(MotivationResolver.playableRecordingId(ringSession(testConfig())))
    }

    /** Lists [recordings] (or [never] emits); counts the reads. */
    private class Listed(
        private val recordings: List<Recording>,
        private val never: Boolean = false,
    ) : RecordingRepository {
        var reads = 0

        override fun observeAll(): Flow<List<Recording>> {
            reads++
            return if (never) flow { awaitCancellation() } else flowOf(recordings)
        }

        override suspend fun get(id: String): Outcome<Recording?, DomainError> = Outcome.Success(recordings.firstOrNull { it.id == id })

        override suspend fun save(
            recording: Recording,
            take: RecordedTake,
        ): Outcome<Unit, DomainError> = Outcome.Success(Unit)

        override suspend fun delete(id: String): Outcome<Unit, DomainError> = Outcome.Success(Unit)
    }

    /** A repository whose list throws [error]. */
    private class Failing(
        private val error: Exception = IllegalStateException("disk"),
    ) : RecordingRepository {
        override fun observeAll(): Flow<List<Recording>> = flow { throw error }

        override suspend fun get(id: String): Outcome<Recording?, DomainError> = Outcome.Failure(DomainError.StorageFailure("disk"))

        override suspend fun save(
            recording: Recording,
            take: RecordedTake,
        ): Outcome<Unit, DomainError> = Outcome.Success(Unit)

        override suspend fun delete(id: String): Outcome<Unit, DomainError> = Outcome.Success(Unit)
    }
}

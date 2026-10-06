package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionMergeRow
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.time.Deadline
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Story 2.9 (FR-SES-7): an alarm that rings during a session joins it, and the merge is recorded once in session_merge. */
class SessionMergeTest {
    private val time = EngineTime()
    private val store = InMemorySessionStore()
    private val runner = RecordingRunner()
    private val history = InMemoryHistory()
    private val logger = EngineLogger()
    private val engine =
        SessionEngine(
            productionReducer(),
            store,
            runner,
            SessionRecorder(history),
            time.clock,
            time.monotonicClock,
            time.bootCounter,
            logger,
        )

    private val alarmB = SessionEvent.OverlapAlarmFired("alarm-b", Instant.parse("2027-03-03T06:01:00Z"))

    private fun Outcome<SessionState, *>.state(): SessionState = assertIs<Outcome.Success<SessionState>>(this).value

    private fun expectedRow() =
        SessionMergeRow(SESSION_ID, "alarm-b", alarmB.scheduledAt, Instant.fromEpochMilliseconds(time.now.wallMillis))

    @Test
    fun `in Ringing, Grace or Loud a merge changes nothing in the session and writes one merge row through the recorder`() =
        runTest {
            ringStates().forEach { ring ->
                val phone = SessionMergeTest()
                phone.store.commit(ring)
                phone.engine.restore()
                val before = phone.engine.state.value
                phone.runner.ran.clear()

                val after = phone.engine.dispatch(phone.alarmB).state()

                assertEquals(before, after, "${ring.kind}: state, config, check progress, grace and timeout unchanged")
                assertEquals(
                    listOf(phone.expectedRow()),
                    phone.history.mergeRows.values
                        .toList(),
                    ring.kind,
                )
                assertTrue(phone.runner.oneShot.none { it is SessionEffect.RecordMergedOccurrence }, "never the runner's")
                assertTrue(SessionEffect.RescheduleAlarm("alarm-b") in phone.runner.oneShot)
            }
        }

    @Test
    fun `a merge replayed after a crash leaves one row with the first merge time`() =
        runTest {
            store.commit(SessionState.Ringing(ringSession()))
            engine.dispatch(alarmB)
            val first = expectedRow()
            time.advanceBy(30.seconds)

            engine.dispatch(alarmB)

            assertEquals(2, history.mergeCalls)
            assertEquals(listOf(first), history.mergeRows.values.toList())
        }

    @Test
    fun `the merge row is written before the commit, so a process killed right after the commit keeps it`() =
        runTest {
            store.commit(SessionState.Snoozed(snoozedSession()))
            engine.restore()
            val rowsAtCommit = mutableListOf<Int>()
            store.onCommit = { rowsAtCommit += history.mergeRows.size }

            assertIs<SessionState.Ringing>(engine.dispatch(alarmB).state())

            assertEquals(1, rowsAtCommit.first(), "the row exists when the merge commits")
        }

    @Test
    fun `a merge whose commit fails keeps its row, and the merge dispatched again leaves one row with the first time`() =
        runTest {
            store.commit(SessionState.Snoozed(snoozedSession()))
            engine.restore()
            store.commitFailure = DomainError.StorageFailure("disk full")
            assertIs<Outcome.Failure<DomainError>>(engine.dispatch(alarmB))
            val first = expectedRow()
            store.commitFailure = null
            time.advanceBy(30.seconds)

            assertIs<SessionState.Ringing>(engine.dispatch(alarmB).state())

            assertEquals(listOf(first), history.mergeRows.values.toList())
        }

    @Test
    fun `during a snooze a merge rings now as the next ring with no grace window and no fee, and I'm up goes straight to Loud`() =
        runTest {
            val snoozed = SessionState.Snoozed(snoozedSession())
            store.commit(snoozed)
            engine.restore()
            time.advanceBy(2.minutes)

            val ringing = assertIs<SessionState.Ringing>(engine.dispatch(alarmB).state())

            assertEquals(2, ringing.session.ringIndex)
            assertTrue(ringing.session.noGraceThisRing)
            assertEquals(snoozed.session.snoozesGranted, ringing.session.snoozesGranted, "no fee")
            assertEquals(Deadline.after(time.now, 30.minutes), ringing.session.interactionDeadline, "a fresh 30-minute deadline")
            assertEquals(
                SessionEffect.ArmSlot(Deadline.after(time.now, SessionReducer.HEARTBEAT)),
                runner.oneShot.first {
                    it is SessionEffect.ArmSlot
                },
            )
            assertEquals(listOf(expectedRow()), history.mergeRows.values.toList())
            assertIs<SessionState.Loud>(engine.dispatch(SessionEvent.ImUpTapped).state(), "no grace window")
        }

    @Test
    fun `a merge row that cannot be written is logged and the merge stands`() =
        runTest {
            val ringing = SessionState.Ringing(ringSession())
            store.commit(ringing)
            history.mergeFailure = DomainError.StorageFailure("disk full")

            assertEquals(ringing, engine.dispatch(alarmB).state())

            assertTrue(logger.events.contains(LogEvent.OperationFailed("record merged occurrence", "storage failure: disk full")))
        }

    @Test
    fun `merges are read back per session`() =
        runTest {
            store.commit(SessionState.Ringing(ringSession()))
            engine.dispatch(alarmB)

            assertEquals(Outcome.Success(listOf(expectedRow())), history.merges(SESSION_ID))
            assertEquals(Outcome.Success(emptyList()), history.merges("other"))
        }
}

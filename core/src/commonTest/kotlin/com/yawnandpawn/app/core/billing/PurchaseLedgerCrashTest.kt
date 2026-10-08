package com.yawnandpawn.app.core.billing

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Story 4.10: the process dies at every point between the two databases (`runtime.db`'s ledger and `app.db`'s records)
 * and Play, then a new process replays the ledger. Every case ends with exactly one record, consumed (or reused), the
 * ledger row gone and Play holding nothing; a repeat consume is the only cost.
 */
class PurchaseLedgerCrashTest {
    /** One crash: where it happens (null: right after the commit, before any settle) and the consumes in total. */
    private data class Crash(
        val at: CrashPoint?,
        val consumes: Int,
    )

    private val crashes =
        listOf(
            Crash(at = null, consumes = 1),
            Crash(CrashPoint.AfterRecordUpsert, consumes = 1),
            Crash(CrashPoint.AfterConsume, consumes = 2),
            Crash(CrashPoint.AfterLedgerConsumed, consumes = 1),
            Crash(CrashPoint.AfterRecordConsumed, consumes = 1),
        )

    private suspend fun crashThenReplay(
        crash: Crash,
        world: LedgerWorld,
    ) {
        world.play.owned += TOKEN_1
        world.ledgerRows[TOKEN_1] = grant(snoozeNumber = 1)
        if (crash.at != null) {
            world.crashAt = crash.at
            assertFailsWith<ProcessDied>("$crash") { world.ledger().settle(TOKEN_1) }
        }
        // A new process: app start replays the ledger.
        assertEquals(SettleResult.Settled, world.ledger().settleAll(), "$crash")
    }

    @Test
    fun `a grant settles to one consumed record wherever the process dies`() =
        runTest {
            crashes.forEach { crash ->
                val world = LedgerWorld()

                crashThenReplay(crash, world)

                assertEquals(1, world.recordRows.size, "$crash: no duplicate record")
                assertEquals(RecordStatus.Consumed, world.recordOf().status, "$crash")
                assertTrue(world.ledgerRows.values.all { it.settledAt != null }, "$crash: the ledger row is a settled marker")
                assertTrue(world.play.owned.isEmpty(), "$crash: consumed on Play")
                assertEquals(List(crash.consumes) { TOKEN_1 }, world.play.calls, "$crash")
                // A second replay (the next start) finds nothing to do.
                assertEquals(SettleResult.Settled, world.ledger().settleAll())
                assertEquals(crash.consumes, world.play.calls.size, "$crash: never consumed again")
            }
        }

    @Test
    fun `a reused stranded payment settles to one reused record wherever the process dies`() =
        runTest {
            // A reused record is never marked consumed, so there is no crash point after that write.
            crashes.filter { it.at != CrashPoint.AfterRecordConsumed }.forEach { crash ->
                val world = LedgerWorld()
                world.recordRows[TOKEN_1.hash()] = record(RecordStatus.Stranded)

                crashThenReplay(crash, world)

                assertEquals(1, world.recordRows.size, "$crash")
                val reused = world.recordOf()
                assertEquals(RecordStatus.Reused, reused.status, "$crash")
                assertEquals(SESSION, reused.sessionId, "$crash")
                assertTrue(world.ledgerRows.values.all { it.settledAt != null }, "$crash")
                assertTrue(world.play.owned.isEmpty(), "$crash")
            }
        }

    @Test
    fun `a ConsumeOnly that dies after Play consumed it is consumed again harmlessly by the next recovery`() =
        runTest {
            val world = LedgerWorld()
            world.play.owned += TOKEN_1
            world.recordRows[TOKEN_1.hash()] = record(RecordStatus.Granted)
            world.crashAt = CrashPoint.AfterConsume

            assertFailsWith<ProcessDied> { world.ledger().consumeOnly(paidSnapshot()) }
            assertEquals(RecordStatus.Granted, world.recordOf().status)

            assertEquals(SettleResult.Settled, world.ledger().consumeOnly(paidSnapshot()))
            assertEquals(RecordStatus.Consumed, world.recordOf().status)
            assertEquals(2, world.play.calls.size)
        }
}

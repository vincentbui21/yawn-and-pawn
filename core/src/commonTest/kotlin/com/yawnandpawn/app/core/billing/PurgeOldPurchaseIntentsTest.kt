package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.time.Clock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Story 4.8: intents are kept 7 days, then purged on app start. */
class PurgeOldPurchaseIntentsTest {
    private val now = Instant.parse("2027-03-10T06:00:00Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }
    private val logged = mutableListOf<LogEvent>()
    private val logger = Logger { logged += it }

    private class RecordingStore : PurchaseIntentStore {
        val ages = mutableMapOf<String, Instant>()
        var failure: DomainError? = null
        var purgedBefore: Instant? = null

        override suspend fun get(intentId: PurchaseIntentId): Outcome<PurchaseIntent, DomainError> = error("unused")

        override suspend fun forSession(sessionId: String): Outcome<List<PurchaseIntent>, DomainError> = error("unused")

        override suspend fun forProduct(
            sessionId: String,
            productId: String,
        ): Outcome<List<PurchaseIntent>, DomainError> = error("unused")

        override suspend fun purgeOlderThan(instant: Instant): Outcome<Int, DomainError> {
            failure?.let { return Outcome.Failure(it) }
            purgedBefore = instant
            val old = ages.filterValues { it < instant }.keys
            old.forEach(ages::remove)
            return Outcome.Success(old.size)
        }
    }

    @Test
    fun `the retention is 7 days`() = assertEquals(7.days, PurchaseIntent.RETENTION)

    @Test
    fun `intents created more than 7 days ago are purged and younger ones are kept`() =
        runTest {
            val cases: List<Pair<Duration, Boolean>> =
                listOf(
                    0.hours to false,
                    6.days to false,
                    7.days - 1.milliseconds to false,
                    7.days to false,
                    7.days + 1.milliseconds to true,
                    30.days to true,
                )
            val store = RecordingStore()
            cases.forEachIndexed { i, (age, _) -> store.ages["intent-$i"] = now - age }

            val purged = PurgeOldPurchaseIntents(store, clock, logger)()

            assertEquals(Outcome.Success(cases.count { it.second }), purged)
            assertEquals(now - 7.days, store.purgedBefore)
            cases.forEachIndexed { i, (age, gone) -> assertEquals(!gone, "intent-$i" in store.ages, "age $age") }
            assertEquals(emptyList(), logged)
        }

    @Test
    fun `a purge that fails is logged and returned, never thrown`() =
        runTest {
            val store = RecordingStore().apply { failure = DomainError.StorageFailure("locked") }

            assertEquals(Outcome.Failure(DomainError.StorageFailure("locked")), PurgeOldPurchaseIntents(store, clock, logger)())
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("purge purchase intents", "storage failure: locked")), logged)
        }
}

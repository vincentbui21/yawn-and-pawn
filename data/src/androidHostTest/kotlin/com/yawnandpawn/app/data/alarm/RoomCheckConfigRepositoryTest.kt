package com.yawnandpawn.app.data.alarm

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmWithChecks
import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.core.alarm.orderedEntries
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.data.db.AppDatabase
import com.yawnandpawn.app.data.db.AppDatabaseConstructor
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.testing.checkConfigsOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Story 3.5: the `check_config` table, stored with its alarm in one transaction and removed with it. */
@RunWith(RobolectricTestRunner::class)
class RoomCheckConfigRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database =
        Room
            .inMemoryDatabaseBuilder<AppDatabase>(context, factory = { AppDatabaseConstructor.initialize() })
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val alarms = RoomAlarmRepository(database.alarmDao())
    private val repository = RoomCheckConfigRepository(database.checkConfigDao())

    private val math = CheckEntry(CheckType.Math, Difficulty.Hard, count = 7)

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `an alarm saved with its checks reads back with its mode and every field of each row`() =
        runTest(timeout = TIMEOUT) {
            val alarm = anAlarm(id = "a").copy(checkMode = CheckMode.All)
            val rows = checkConfigsOf(alarm.id, listOf(math))

            assertEquals(Outcome.Success(Unit), repository.saveWithAlarm(alarm, rows))

            assertEquals(Outcome.Success(alarm), alarms.get("a"))
            assertEquals(Outcome.Success(rows), repository.forAlarm("a"))
            assertEquals(listOf(AlarmWithChecks(alarm, rows)), repository.observeAlarmsWithChecks().first())
        }

    @Test
    fun `saving again replaces the rows and updates the alarm without touching other alarms`() =
        runTest(timeout = TIMEOUT) {
            val alarm = anAlarm(id = "a")
            val other = anAlarm(id = "b", requestCode = 1001)
            repository.saveWithAlarm(alarm, checkConfigsOf("a", listOf(math)))
            repository.saveWithAlarm(other, checkConfigsOf("b", listOf(math)))
            val easy = math.copy(difficulty = Difficulty.Easy, count = 1)

            repository.saveWithAlarm(alarm.copy(label = "Gym"), checkConfigsOf("a", listOf(easy)))

            assertEquals("Gym", assertIs<Outcome.Success<Alarm>>(alarms.get("a")).value.label)
            assertEquals(listOf(easy), assertIs<Outcome.Success<List<CheckConfig>>>(repository.forAlarm("a")).value.orderedEntries())
            assertEquals(listOf(math), assertIs<Outcome.Success<List<CheckConfig>>>(repository.forAlarm("b")).value.orderedEntries())
        }

    @Test
    fun `a row that cannot be stored rolls the whole save back, the alarm included`() =
        runTest(timeout = TIMEOUT) {
            val alarm = anAlarm(id = "a")
            val clash = checkConfigsOf("a", listOf(math)).single()

            val failed = repository.saveWithAlarm(alarm, listOf(clash, clash.copy(position = 1)))

            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(failed).error)
            assertEquals(Outcome.Failure(DomainError.NotFound("a")), alarms.get("a"))
            assertEquals(Outcome.Success(emptyList()), repository.forAlarm("a"))
        }

    @Test
    fun `deleting the alarm deletes its rows through the foreign key`() =
        runTest(timeout = TIMEOUT) {
            repository.saveWithAlarm(anAlarm(id = "a"), checkConfigsOf("a", listOf(math)))

            assertEquals(Outcome.Success(Unit), alarms.delete("a"))

            assertEquals(Outcome.Success(emptyList()), repository.forAlarm("a"))
        }

    @Test
    fun `alarms and their checks are observed as one read, so a saved alarm never shows without its rows (review fix)`() =
        runTest(timeout = TIMEOUT) {
            val seen: MutableList<List<AlarmWithChecks>> = Collections.synchronizedList(mutableListOf())
            val collecting = backgroundScope.launch(Dispatchers.Default) { repository.observeAlarmsWithChecks().collect { seen += it } }
            withContext(Dispatchers.Default) { withTimeout(TIMEOUT) { while (seen.isEmpty()) delay(10) } }
            val alarm = anAlarm(id = "a")
            val rows = checkConfigsOf("a", listOf(math))

            repository.saveWithAlarm(alarm, rows)
            withContext(Dispatchers.Default) { withTimeout(TIMEOUT) { while (seen.last().isEmpty()) delay(10) } }
            collecting.cancel()

            assertEquals(listOf(AlarmWithChecks(alarm, rows)), seen.last())
            assertTrue(seen.flatten().all { it.checks.isNotEmpty() }, "never the alarm without its rows: $seen")
        }

    @Test
    fun `deleteWithAlarm removes the alarm and its rows together, and an unknown alarm is NotFound (review fix)`() =
        runTest(timeout = TIMEOUT) {
            repository.saveWithAlarm(anAlarm(id = "a"), checkConfigsOf("a", listOf(math)))
            repository.saveWithAlarm(anAlarm(id = "b", requestCode = 1001), checkConfigsOf("b", listOf(math)))

            assertEquals(Outcome.Success(Unit), repository.deleteWithAlarm("a"))

            assertEquals(Outcome.Failure(DomainError.NotFound("a")), alarms.get("a"))
            assertEquals(Outcome.Success(emptyList()), repository.forAlarm("a"))
            assertEquals(Outcome.Success(checkConfigsOf("b", listOf(math))), repository.forAlarm("b"), "other alarms keep theirs")
            assertEquals(Outcome.Failure(DomainError.NotFound("a")), repository.deleteWithAlarm("a"))
        }

    @Test
    fun `rows read back by position, and a row of an unknown type or difficulty is skipped`() =
        runTest(timeout = TIMEOUT) {
            val alarm = anAlarm(id = "a")
            val row = checkConfigsOf("a", listOf(math)).single()
            repository.saveWithAlarm(alarm, listOf(row.copy(position = 2)))
            database.checkConfigDao().insert(
                listOf(
                    row.toEntity().copy(id = "future", type = "HouseHunt", position = 0),
                    row.toEntity().copy(id = "odd", difficulty = "Extreme", position = 1),
                ),
            )

            assertEquals(Outcome.Success(listOf(row.copy(position = 2))), repository.forAlarm("a"))
            assertEquals(listOf(AlarmWithChecks(alarm, listOf(row.copy(position = 2)))), repository.observeAlarmsWithChecks().first())
        }

    @Test
    fun `a stored mode this build does not know reads as Random`() =
        runTest(timeout = TIMEOUT) {
            database.alarmDao().insert(anAlarm(id = "a").toEntity().copy(checkMode = "Shuffle"))

            assertEquals(CheckMode.Random, assertIs<Outcome.Success<Alarm>>(alarms.get("a")).value.checkMode)
        }

    @Test
    fun `on a closed database every call is a storage failure`() =
        runTest(timeout = TIMEOUT) {
            database.close()

            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(repository.forAlarm("a")).error)
            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(repository.deleteWithAlarm("a")).error)
            assertIs<DomainError.StorageFailure>(
                assertIs<Outcome.Failure<DomainError>>(repository.saveWithAlarm(anAlarm(), emptyList())).error,
            )
        }

    @Test
    fun `the alarm use cases store, copy and delete the checks over Room`() =
        runTest(timeout = TIMEOUT) {
            val fixture =
                AlarmUseCasesFixture(
                    repository = alarms,
                    requestCodes = RoomRequestCodeSequence(database.requestCodeSequenceDao()),
                    checkConfigs = repository,
                )
            val draft = AlarmDraft(time = LocalTime(7, 0), checks = listOf(math), checkMode = CheckMode.All)
            val alarm = assertIs<Outcome.Success<Alarm>>(fixture.save(draft)).value

            val copy = assertIs<Outcome.Success<Alarm>>(fixture.duplicate(alarm.id)).value
            assertEquals(Outcome.Success(Unit), fixture.delete(alarm.id))

            assertEquals(Outcome.Success(emptyList()), repository.forAlarm(alarm.id))
            assertEquals(listOf(math), assertIs<Outcome.Success<List<CheckConfig>>>(repository.forAlarm(copy.id)).value.orderedEntries())
            assertEquals(CheckMode.All, copy.checkMode)
        }

    private companion object {
        /** Real Room IO under a loaded gate run, as in `RoomAlarmRepositoryTest`. */
        val TIMEOUT = 5.minutes
    }
}

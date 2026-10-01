package com.yawnandpawn.app.data.session

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SessionJson
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.data.db.RuntimeDatabase
import com.yawnandpawn.app.data.db.RuntimeDatabaseConstructor
import com.yawnandpawn.app.testing.DEFAULT_FAKE_INSTANT
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.everySessionState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class RoomActiveSessionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database =
        Room
            .inMemoryDatabaseBuilder<RuntimeDatabase>(context, factory = { RuntimeDatabaseConstructor.initialize() })
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val dao = database.activeSessionDao()
    private val clock = FakeClock()
    private val store = RoomActiveSessionStore(dao, clock)

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `nothing stored loads as empty`() =
        runTest {
            assertEquals(Outcome.Success(StoredSession.Empty), store.load())
        }

    @Test
    fun `every state variant round trips through the store`() =
        runTest {
            everySessionState().forEach { state ->
                assertEquals(Outcome.Success(Unit), store.commit(state))
                val expected = if (state == SessionState.Idle) StoredSession.Empty else StoredSession.Found(state)
                assertEquals(Outcome.Success(expected), store.load(), state.toString())
            }
        }

    @Test
    fun `the store keeps at most one row, keyed by the session id, with the commit time`() =
        runTest {
            store.commit(SessionState.Ringing(aSession(sessionId = "a")))
            clock.advanceBy(5.seconds)
            val second = SessionState.Loud(aSession(sessionId = "b"))
            store.commit(second)

            assertEquals(1, dao.count())
            val row = dao.get()!!
            assertEquals("b", row.sessionId)
            assertEquals(SessionJson.encode(second), row.stateJson)
            assertEquals((DEFAULT_FAKE_INSTANT + 5.seconds).toEpochMilliseconds(), row.updatedAt)
        }

    @Test
    fun `committing Idle deletes the row and clear deletes it too`() =
        runTest {
            store.commit(SessionState.Ringing(aSession()))
            store.commit(SessionState.Idle)
            assertEquals(0, dao.count())

            store.commit(SessionState.Ringing(aSession()))
            assertEquals(Outcome.Success(Unit), store.clear())
            assertEquals(0, dao.count())
        }

    @Test
    fun `an undecodable row loads as unreadable without its text`() =
        runTest {
            dao.insert(ActiveSessionEntity("s", "{\"type\":\"Ringing\",\"label\":\"Work\"", updatedAt = 0))

            val unreadable = assertIs<StoredSession.Unreadable>(assertIs<Outcome.Success<StoredSession>>(store.load()).value)
            assertEquals(false, "Work" in unreadable.cause)
        }

    @Test
    fun `a closed database is a storage failure for every call, not an exception`() =
        runTest {
            database.close()

            listOf(store.load(), store.commit(SessionState.Ringing(aSession())), store.commit(SessionState.Idle), store.clear())
                .forEach { assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(it).error) }
        }
}

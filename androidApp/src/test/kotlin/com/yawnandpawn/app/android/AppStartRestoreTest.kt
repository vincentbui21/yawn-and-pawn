package com.yawnandpawn.app.android

import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.core.session.entryEffects
import com.yawnandpawn.app.stopApp
import com.yawnandpawn.app.testing.aSession
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AD-2 rule 2: `YawnAndPawnApp.onCreate` takes over the session the last process left in runtime.db. */
@RunWith(RobolectricTestRunner::class)
class AppStartRestoreTest {
    @get:Rule(order = 0)
    val stopAppRule = StopAppRule()

    private val app = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()

    private fun awaitStartUp() = GlobalContext.get().get<ApplicationScope>().awaitChildren()

    @Test
    fun `app start restores a persisted session, dispatches ProcessRestored and runs only entry effects`() {
        awaitStartUp()
        // The last process committed a ringing session with a payment in flight to runtime.db, then died.
        val persisted = SessionState.Ringing(aSession().copy(paying = PurchaseIntentId("intent-1")))
        val written = runBlocking { GlobalContext.get().get<ActiveSessionStore>().commit(persisted) }
        assertEquals(Outcome.Success(Unit), written)
        stopApp()
        ShadowLog.clear()

        app.onCreate()
        awaitStartUp()

        val koin = GlobalContext.get()
        val restored = assertIs<SessionState.Ringing>(koin.get<SessionEngine>().state.value)
        assertEquals(persisted.session.sessionId, restored.session.sessionId)
        assertNull(restored.session.paying, "billing is never relaunched")
        assertNotEquals(persisted.session.interactionDeadline, restored.session.interactionDeadline, "a fresh 30-minute deadline")
        val stored = runBlocking { koin.get<ActiveSessionStore>().load() }
        assertEquals(Outcome.Success(StoredSession.Found(restored)), stored, "ProcessRestored is committed")

        val effectLogs = ShadowLog.getLogsForTag(AndroidLogger.TAG).map { it.msg }.filter { it.startsWith("SessionEffectLogged") }
        val expected = entryEffects(restored).map { "SessionEffectLogged type=${it::class.simpleName} entry=true" }
        assertEquals(expected, effectLogs)
        assertTrue(effectLogs.none { "entry=false" in it }, "no one-shot effect ran: $effectLogs")
    }
}

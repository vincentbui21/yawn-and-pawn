package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.billing.PurchaseCoordinator
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.UnlockPort
import com.yawnandpawn.app.core.session.UnlockResult
import com.yawnandpawn.app.testing.FakeBilling
import com.yawnandpawn.app.testing.FakeSnoozeAvailability
import com.yawnandpawn.app.testing.FakeUnlockPort
import com.yawnandpawn.app.testing.aPayConfirmed
import com.yawnandpawn.app.testing.aSessionConfig
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Story 4.11 review 10: in the app's own Koin graph, a locked Pay reaches the one `UnlockPort` through the wake runtime
 * and the coordinator, and an unlock opens Play through the same path.
 */
@RunWith(RobolectricTestRunner::class)
class BillingWiringTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val unlock = FakeUnlockPort(locked = true).apply { hold = true }
    private val billing = FakeBilling()
    private val app = WakeApp(billing = billing, policy = FakeSnoozeAvailability(), unlock = unlock)

    @Test
    fun `a locked Pay asks the shared unlock port through the coordinator, and the unlock opens Play`() {
        assertSame<UnlockPort>(unlock, app.koin.get<UnlockPort>(), "one UnlockPort binding")
        app.koin.get<PurchaseCoordinator>()
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(), beforeFirstUnlock = false))
        app.awaitRinging()

        app.dispatch(SessionEvent.SnoozeTapped, aPayConfirmed())
        app.awaitUntil("the coordinator asked for the unlock") { unlock.requests == 1 }
        assertTrue(assertIs<SessionState.Ringing>(app.engine.state.value).session.unlocking)
        assertEquals(emptyList(), billing.launched, "Play waits for the unlock")

        unlock.release(UnlockResult.Succeeded)
        app.awaitUntil("Play opened after the unlock") { billing.launched.size == 1 }
        app.dispatch(SessionEvent.ImUpTapped)
        app.solveCheck()
    }
}

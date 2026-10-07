package com.yawnandpawn.app.android.wake

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.buildActivity
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.aSession
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Story 3.3 review: when the wake screen shows Success or closes, across a recreation and a kill. */
@RunWith(RobolectricTestRunner::class)
class WakeScreenEndTest {
    @get:Rule
    val stopApp = StopAppRule()

    private var ended: SessionState.Active? = null
    private var now = 1_000L

    private fun completed(id: String) = SessionState.Completed(aSession(sessionId = id))

    private fun screen(saved: Bundle? = null): Pair<ActivityController<ComponentActivity>, WakeScreenEnd> {
        val controller = buildActivity(ComponentActivity::class.java).create(saved)
        return controller to WakeScreenEnd(controller.get(), ended = { ended }, elapsedMillis = { now })
    }

    /** A session "a" rang on [end] and completed: Success shows. */
    private fun successFor(end: WakeScreenEnd) {
        end.follow(active = true, ringingSessionId = "a", restored = true)
        ended = completed("a")
        end.follow(active = false, ringingSessionId = null, restored = true)
        assertEquals("a", end.success()?.sessionId)
    }

    /** Saves [controller]'s state as the system does before a recreation or a kill. */
    private fun saved(controller: ActivityController<ComponentActivity>): Bundle = Bundle().also { controller.saveInstanceState(it) }

    @Test
    fun `recreated after a kill, it waits while the engine is not restored, and closes only once restored Idle`() {
        val (first, before) = screen()
        before.follow(active = true, ringingSessionId = "a", restored = true)
        val bundle = saved(first)
        val (controller, end) = screen(bundle)

        // A new process: the engine is Idle only because the stored session is not loaded yet.
        end.follow(active = false, ringingSessionId = null, restored = false)
        assertFalse(controller.get().isFinishing, "waits for the restore")

        end.follow(active = false, ringingSessionId = null, restored = true)
        assertTrue(controller.get().isFinishing, "nothing was restored: the ring is over")
    }

    @Test
    fun `an emergency ring after Success forgets the completed session, so its end shows no Success again`() {
        val (controller, end) = screen()
        successFor(end)

        end.follow(active = true, ringingSessionId = null, restored = true)
        assertNull(end.success(), "the emergency ring replaces Success")
        end.follow(active = false, ringingSessionId = null, restored = true)

        assertNull(end.successSessionId, "no Success for the emergency ring")
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun `the time left on Success counts from when it started, also after a recreation`() {
        val (first, before) = screen()
        successFor(before)
        now += 40_000
        assertEquals(20.seconds, before.successRemaining(60.seconds))

        val (_, end) = screen(saved(first))
        now += 5_000

        assertEquals("a", end.success()?.sessionId, "Success is kept")
        assertEquals(15.seconds, end.successRemaining(60.seconds))
    }

    @Test
    fun `the success haptic is claimed once per session, also after a recreation`() {
        val (first, before) = screen()
        successFor(before)
        assertTrue(before.claimHaptic("a"))
        assertFalse(before.claimHaptic("a"), "Success re-entering composition")

        val (_, end) = screen(saved(first))

        assertFalse(end.claimHaptic("a"), "after a recreation")
        assertTrue(end.claimHaptic("b"), "a later session plays its own")
    }
}

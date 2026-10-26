package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

/**
 * Story 5.13 device check: the wake service's test start (`ACTION_TEST`) enters the foreground with the full ringing
 * notification for the test's ring time, so SystemUI launches its full-screen intent on that first post. (Kept out of
 * `WakeServiceTest`, which is at detekt's LargeClass limit.)
 */
@RunWith(RobolectricTestRunner::class)
class WakeServiceTestStartTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val scheduledAt = Instant.parse("2027-03-08T06:00:00Z")

    @Test
    fun `a test start's first foreground notification is the full ringing one with its full-screen intent (Story 5_13)`() {
        // The test's session load waits, so the foreground notification is read before anything else is posted.
        val loadAllowed = CompletableDeferred<Unit>()
        val empty = FakeActiveSessionStore()
        val gated =
            object : ActiveSessionStore by empty {
                override suspend fun load(): Outcome<StoredSession, DomainError> {
                    loadAllowed.await()
                    return empty.load()
                }
            }
        val app = WakeApp(store = gated)

        val service = app.startService(WakeService.testIntent(app.app, scheduledAt)).get()

        val first = assertNotNull(shadowOf(service).lastForegroundNotification, "in the foreground")
        assertEquals(WakeNotifier.CHANNEL_ID, first.channelId)
        assertNotNull(first.fullScreenIntent, "the first post opens the wake screen")
        assertEquals(scheduledAt, app.runtime.shownAlarmAt())
        loadAllowed.complete(Unit)
        app.awaitUntil("nothing pending: the service stops") { shadowOf(service).isStoppedBySelf }
    }

    @Test
    fun `a test start without its ring time falls back to the quiet notification`() {
        val loadAllowed = CompletableDeferred<Unit>()
        val empty = FakeActiveSessionStore()
        val gated =
            object : ActiveSessionStore by empty {
                override suspend fun load(): Outcome<StoredSession, DomainError> {
                    loadAllowed.await()
                    return empty.load()
                }
            }
        val app = WakeApp(store = gated)

        val service = app.startService(WakeService.intent(app.app, WakeService.ACTION_TEST)).get()

        assertEquals(WakeNotifier.QUIET_CHANNEL_ID, assertNotNull(shadowOf(service).lastForegroundNotification).channelId)
        loadAllowed.complete(Unit)
        app.awaitUntil("nothing pending: the service stops") { shadowOf(service).isStoppedBySelf }
    }
}

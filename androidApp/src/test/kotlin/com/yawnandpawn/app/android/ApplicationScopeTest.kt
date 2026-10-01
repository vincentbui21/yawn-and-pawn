package com.yawnandpawn.app.android

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.testing.FakeLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Deferred from Story 1.10: the app-wide scope logs an unexpected exception and keeps the other jobs alive. */
class ApplicationScopeTest {
    @Test
    fun `an exception in one job is logged and its sibling jobs keep running`() =
        runTest {
            val logger = FakeLogger()
            val scope = ApplicationScope(logger, StandardTestDispatcher(testScheduler))
            val sibling = scope.launch { delay(10.seconds) }

            scope.launch { error("boom") }
            advanceTimeBy(1.seconds)
            runCurrent()

            assertTrue(sibling.isActive, "the sibling is not cancelled")
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("background work", "boom")), logger.events)
            advanceUntilIdle()
            assertTrue(sibling.isCompleted)
            assertFalse(sibling.isCancelled, "the sibling ran to its end")
            val later = scope.launch { }
            advanceUntilIdle()
            assertTrue(later.isCompleted, "the scope still takes new work")
        }
}

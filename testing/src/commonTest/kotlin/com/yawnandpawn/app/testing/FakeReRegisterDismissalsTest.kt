package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.stats.CheckKey
import com.yawnandpawn.app.core.stats.ReRegisterInputs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours

class FakeReRegisterDismissalsTest {
    private val key = CheckKey("alarm", "QrBarcode")

    @Test
    fun `the fake dismissals keep the latest time per check, and a failure stores nothing`() =
        runTest {
            val dismissals = FakeReRegisterDismissals()

            assertEquals(Outcome.Success(Unit), dismissals.dismiss(key, DEFAULT_FAKE_INSTANT))
            dismissals.dismissFailure = DomainError.StorageFailure("disk full")
            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk full")), dismissals.dismiss(key, DEFAULT_FAKE_INSTANT + 1.hours))

            assertEquals(mapOf(key to DEFAULT_FAKE_INSTANT), dismissals.dismissed().first())
        }

    @Test
    fun `nothing registered suggests nothing`() =
        runTest {
            val suggestions = noReRegisterSuggestions()

            assertEquals(ReRegisterInputs(), suggestions.inputs().first())
            assertNull(suggestions.suggestion(suggestions.inputs().first(), listOf(anAlarm())))
        }
}

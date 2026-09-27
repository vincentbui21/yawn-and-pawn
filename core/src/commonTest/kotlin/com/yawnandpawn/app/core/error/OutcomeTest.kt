package com.yawnandpawn.app.core.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutcomeTest {
    private val success: Outcome<Int, DomainError> = Outcome.Success(2)
    private val failure: Outcome<Int, DomainError> = Outcome.Failure(DomainError.NotFound("a"))

    @Test
    fun `valueOrNull and errorOrNull read the side that is present`() {
        assertEquals(2, success.valueOrNull())
        assertNull(success.errorOrNull())
        assertNull(failure.valueOrNull())
        assertEquals(DomainError.NotFound("a"), failure.errorOrNull())
    }

    @Test
    fun `map transforms a success and passes a failure through`() {
        assertEquals(Outcome.Success(4), success.map { it * 2 })
        assertEquals(failure, failure.map { it * 2 })
    }

    @Test
    fun `flatMap chains a success and passes a failure through`() {
        val storage = DomainError.StorageFailure("disk full")

        assertEquals(Outcome.Success("2"), success.flatMap { Outcome.Success(it.toString()) })
        assertEquals(Outcome.Failure(storage), success.flatMap { Outcome.Failure(storage) })
        assertEquals<Outcome<Any, DomainError>>(failure, failure.flatMap { Outcome.Success(it.toString()) })
    }
}

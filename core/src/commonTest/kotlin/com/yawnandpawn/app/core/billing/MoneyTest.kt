package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoneyTest {
    @Test
    fun `a currency is exactly 3 upper-case letters`() {
        listOf("USD", "EUR", "JPY", "VND", "AAA", "ZZZ").forEach { code ->
            assertTrue(Money.isCurrencyCode(code), code)
            assertEquals(Outcome.Success(Money(1, code)), Money.parse(1, code))
        }
        listOf("usd", "Usd", "US", "USDX", "", "U1D", "U D", "ÄBC", "ＵＳＤ").forEach { code ->
            assertFalse(Money.isCurrencyCode(code), code)
            assertEquals(Outcome.Failure(DomainError.InvalidCurrency(code)), Money.parse(1, code), code)
            assertFailsWith<IllegalArgumentException>(code) { Money(1, code) }
        }
    }

    @Test
    fun `plus adds the same currency and returns a mismatch for another, never throwing`() {
        assertEquals(Outcome.Success(Money(3_500_000, "USD")), Money.of(1, "USD") + Money(2_500_000, "USD"))
        assertEquals(Outcome.Success(Money(-1, "EUR")), Money(0, "EUR") + Money(-1, "EUR"))
        assertEquals(Outcome.Failure(DomainError.CurrencyMismatch("USD", "EUR")), Money.of(1, "USD") + Money.of(1, "EUR"))
        assertEquals(Outcome.Failure(DomainError.CurrencyMismatch("EUR", "USD")), Money.of(1, "EUR") + Money.of(1, "USD"))
    }

    @Test
    fun `of counts whole units in micros and times multiplies them`() {
        assertEquals(Money(1_000_000, "USD"), Money.of(1, "USD"))
        assertEquals(Money(150_000_000, "JPY"), Money.of(150, "JPY"))
        assertEquals(Money.of(3, "EUR"), Money.of(1, "EUR") * 3)
        assertEquals(Money(0, "EUR"), Money.of(5, "EUR") * 0)
        assertEquals(Money(25_000_000_000L * 50, "VND"), Money(25_000_000_000L, "VND") * 50)
    }

    @Test
    fun `totals are one amount per currency in first-seen order`() {
        assertEquals(emptyList(), totalsByCurrency(emptyList()))
        assertEquals(listOf(Money.of(1, "USD")), totalsByCurrency(listOf(Money.of(1, "USD"))))
        assertEquals(
            listOf(Money.of(4, "USD"), Money.of(2, "EUR"), Money(5, "JPY")),
            totalsByCurrency(listOf(Money.of(1, "USD"), Money.of(2, "EUR"), Money.of(3, "USD"), Money(5, "JPY"))),
        )
        assertEquals(
            listOf(Money.of(2, "EUR"), Money.of(1, "USD")),
            totalsByCurrency(listOf(Money.of(2, "EUR"), Money.of(1, "USD"))),
        )
        assertEquals(listOf(Money(0, "USD")), totalsByCurrency(listOf(Money.of(1, "USD"), Money(-1_000_000, "USD"))))
    }

    @Test
    fun `the formatter joins one total per currency with a plus`() {
        val formatted = mutableListOf<Money>()
        val formatter =
            MoneyFormatter { money ->
                formatted += money
                "${money.currency}:${money.micros}"
            }

        assertEquals("", formatter.formatTotals(emptyList()))
        assertEquals("USD:1000000", formatter.formatTotals(listOf(Money.of(1, "USD"))))
        assertEquals(
            "USD:4000000 + EUR:2000000",
            formatter.formatTotals(listOf(Money.of(1, "USD"), Money.of(2, "EUR"), Money.of(3, "USD"))),
        )
        assertEquals(listOf(Money.of(1, "USD"), Money.of(4, "USD"), Money.of(2, "EUR")), formatted)
        assertEquals(" + ", TOTALS_SEPARATOR)
    }
}

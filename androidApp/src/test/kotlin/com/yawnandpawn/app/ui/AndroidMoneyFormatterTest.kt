package com.yawnandpawn.app.ui

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.formatTotals
import com.yawnandpawn.app.ui.format.AndroidMoneyFormatter
import com.yawnandpawn.app.ui.format.formatMoney
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.assertEquals

/**
 * Story 4.2: money is formatted in the phone's locale with the currency's own symbol and fraction digits (AD-8). The
 * platform puts a no-break space (U+00A0 or U+202F) between amount and symbol in some locales; [plain] makes it a space.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidMoneyFormatterTest {
    private fun formatter(tag: String) = AndroidMoneyFormatter { Locale.forLanguageTag(tag) }

    private fun plain(text: String): String = text.replace(' ', ' ').replace(' ', ' ')

    @Test
    fun `each currency is formatted in its locale with its own fraction digits`() {
        listOf(
            Triple("en-US", Money(1_000_000, "USD"), "$1.00"),
            Triple("de-DE", Money(1_000_000, "EUR"), "1,00 €"),
            Triple("ja-JP", Money(150_000_000, "JPY"), "￥150"),
            Triple("vi-VN", Money(25_000_000_000, "VND"), "25.000 ₫"),
        ).forEach { (tag, money, expected) ->
            assertEquals(expected, plain(formatter(tag).format(money)), "$money in $tag")
        }
    }

    @Test
    fun `amounts round half-even to the currency's digits, and zero keeps its decimals`() {
        val us = formatter("en-US")
        assertEquals("$1.99", us.format(Money(1_990_000, "USD")))
        assertEquals("$1.23", us.format(Money(1_234_567, "USD")))
        assertEquals("$0.12", us.format(Money(125_000, "USD")))
        assertEquals("$0.00", us.format(Money(0, "USD")))
        assertEquals("$50.00", us.format(Money.of(50, "USD")))
        assertEquals("¥2", us.format(Money(1_500_000, "JPY")))
    }

    @Test
    fun `another currency in the phone's locale keeps that currency's symbol`() {
        assertEquals("€2.00", formatter("en-US").format(Money.of(2, "EUR")))
        assertEquals("2,00 $", plain(formatter("de-DE").format(Money.of(2, "USD"))))
    }

    @Test
    fun `a mixed list is one amount per currency joined with a plus`() {
        val us = formatter("en-US")
        assertEquals("$4.00 + €2.00", us.formatTotals(listOf(Money.of(1, "USD"), Money.of(2, "EUR"), Money.of(3, "USD"))))
        assertEquals("", us.formatTotals(emptyList()))
    }

    @Test
    fun `a code the platform does not know shows the code and two digits`() {
        assertEquals("XYZ 1.50", formatter("en-US").format(Money(1_500_000, "XYZ")))
    }

    @Test
    fun `the UI formats money only through the phone's formatter`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("$3.00", formatMoney(Money.of(3, "USD")))
            assertEquals("$1.00 + €2.00", formatMoney(listOf(Money.of(1, "USD"), Money.of(2, "EUR"))))
        } finally {
            Locale.setDefault(previous)
        }
    }
}

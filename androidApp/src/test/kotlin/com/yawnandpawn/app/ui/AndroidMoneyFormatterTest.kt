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
import kotlin.test.assertFalse

/**
 * Story 4.2: money is formatted in the phone's locale with the currency's own symbol and digits (AD-8), through ICU as
 * on the phone. ICU puts a no-break space (U+00A0 or U+202F) between amount and symbol in some locales; [plain] makes
 * it a space. The phone's own output is checked on a device in Story 4.18 (deferred-work.md).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidMoneyFormatterTest {
    private fun formatter(tag: String) = AndroidMoneyFormatter { Locale.forLanguageTag(tag) }

    private fun plain(text: String): String = text.replace(' ', ' ').replace(' ', ' ')

    private fun format(
        tag: String,
        money: Money,
    ): String = plain(formatter(tag).format(money))

    @Test
    fun `each currency is formatted in its locale with its own fraction digits`() {
        listOf(
            Triple("en-US", Money(1_000_000, "USD"), "$1.00"),
            Triple("de-DE", Money(1_000_000, "EUR"), "1,00 €"),
            Triple("ja-JP", Money(150_000_000, "JPY"), "￥150"),
            Triple("vi-VN", Money(25_000_000_000, "VND"), "25.000 ₫"),
        ).forEach { (tag, money, expected) ->
            assertEquals(expected, format(tag, money), "$money in $tag")
        }
    }

    @Test
    fun `a whole amount uses the cash digits, as Play's price strings do`() {
        assertEquals("Rp 15.000", format("id-ID", Money(15_000_000_000, "IDR")))
        assertEquals("15 000 Ft", format("hu-HU", Money(15_000_000_000, "HUF")))
        assertEquals("$50.00", format("en-US", Money.of(50, "USD")))
        assertEquals("$0.00", format("en-US", Money(0, "USD")))
    }

    @Test
    fun `other amounts use the standard digits and round half-even`() {
        assertEquals("$1.99", format("en-US", Money(1_990_000, "USD")))
        assertEquals("$1.23", format("en-US", Money(1_234_567, "USD")))
        assertEquals("$0.12", format("en-US", Money(125_000, "USD")))
        assertEquals("¥2", format("en-US", Money(1_500_000, "JPY")))
        assertEquals("KWD 1.235", format("en-US", Money(1_234_567, "KWD")))
        assertEquals("Rp 15.000,50", format("id-ID", Money(15_000_500_000, "IDR")))
    }

    @Test
    fun `negative amounts and pseudo-currencies format without crashing`() {
        assertEquals("-$1.00", format("en-US", Money(-1_000_000, "USD")))
        val pseudo = format("en-US", Money(1_500_000, "XXX"))
        assertFalse(pseudo.contains('.'), pseudo)
        assertEquals("€2.00", format("en-US", Money.of(2, "EUR")))
        assertEquals("2,00 $", format("de-DE", Money.of(2, "USD")))
    }

    @Test
    fun `a mixed list is one amount per currency joined with a plus`() {
        val us = formatter("en-US")
        assertEquals("$4.00 + €2.00", us.formatTotals(listOf(Money.of(1, "USD"), Money.of(2, "EUR"), Money.of(3, "USD"))))
        assertEquals("", us.formatTotals(emptyList()))
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

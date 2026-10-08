package com.yawnandpawn.app.ui.format

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.MoneyFormatter
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * The Android [MoneyFormatter] (AD-8, Story 4.2): `NumberFormat.getCurrencyInstance` for the [locale] read at each call
 * (the phone's locale by default, so a language change applies at once), with the currency's own symbol and fraction
 * digits: USD in en-US "$1.00", EUR in de-DE "1,00 €", JPY in ja-JP "￥150", VND in vi-VN "25.000 ₫". An amount finer
 * than the currency's digits rounds half-even. A code the platform does not know is shown as the code and the amount
 * with 2 digits ("XYZ 1.00").
 */
class AndroidMoneyFormatter(
    private val locale: () -> Locale = Locale::getDefault,
) : MoneyFormatter {
    override fun format(money: Money): String {
        val amount = BigDecimal.valueOf(money.micros).movePointLeft(MICRO_DIGITS)
        val currency =
            runCatching { Currency.getInstance(money.currency) }.getOrNull()
                ?: return "${money.currency} ${NumberFormat.getNumberInstance(locale()).withDigits(UNKNOWN_DIGITS).format(amount)}"
        val format = NumberFormat.getCurrencyInstance(locale())
        format.currency = currency
        return format.withDigits(currency.defaultFractionDigits.coerceAtLeast(0)).format(amount)
    }

    private fun NumberFormat.withDigits(digits: Int): NumberFormat =
        apply {
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }

    private companion object {
        /** `Money.MICROS_PER_UNIT` is 10^6. */
        const val MICRO_DIGITS = 6

        /** Fraction digits for a currency the platform does not know. */
        const val UNKNOWN_DIGITS = 2
    }
}

/** The UI's formatter: the phone's locale at each call. */
actual val moneyFormatter: MoneyFormatter = AndroidMoneyFormatter()

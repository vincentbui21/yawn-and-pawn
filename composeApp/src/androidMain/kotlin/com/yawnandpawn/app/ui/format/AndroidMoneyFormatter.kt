package com.yawnandpawn.app.ui.format

import android.icu.text.NumberFormat
import android.icu.util.Currency
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.MoneyFormatter
import java.math.BigDecimal
import java.util.Locale

/**
 * The Android [MoneyFormatter] (AD-8, Story 4.2): ICU's `NumberFormat.getCurrencyInstance` for the [locale] read at
 * each call (the phone's locale by default, so a language change applies at once), with the currency's own symbol and
 * digits: USD in en-US "$1.00", EUR in de-DE "1,00 €", JPY in ja-JP "￥150", VND in vi-VN "25.000 ₫".
 *
 * A whole amount uses the currency's cash digits, as Google Play's own price strings do, so a currency whose coins are
 * gone shows none (IDR in id-ID "Rp 15.000", HUF, TWD) while USD and EUR keep 2. An amount finer than that uses the
 * standard digits and rounds half-even. A pseudo-currency without digits (XXX, XAU) shows none. ICU, not
 * `java.text`, so the host tests (Robolectric) format like the phone.
 */
class AndroidMoneyFormatter(
    private val locale: () -> Locale = Locale::getDefault,
) : MoneyFormatter {
    override fun format(money: Money): String {
        val amount = BigDecimal.valueOf(money.micros).movePointLeft(MICRO_DIGITS)
        val format = NumberFormat.getCurrencyInstance(locale())
        val currency = Currency.getInstance(money.currency)
        format.currency = currency
        val digits =
            when {
                isPseudoCurrency(money.currency) -> 0
                money.micros % Money.MICROS_PER_UNIT == 0L -> currency.getDefaultFractionDigits(Currency.CurrencyUsage.CASH)
                else -> currency.getDefaultFractionDigits(Currency.CurrencyUsage.STANDARD)
            }.coerceAtLeast(0)
        format.minimumFractionDigits = digits
        format.maximumFractionDigits = digits
        return format.format(amount)
    }

    /** ISO 4217 codes with no minor unit at all (`java.util.Currency` reports -1): XXX, XAU, XDR and the like. */
    private fun isPseudoCurrency(code: String): Boolean =
        runCatching {
            java.util.Currency
                .getInstance(code)
                .defaultFractionDigits < 0
        }.getOrDefault(false)

    private companion object {
        /** `Money.MICROS_PER_UNIT` is 10^6. */
        const val MICRO_DIGITS = 6
    }
}

/** The UI's formatter: the phone's locale at each call. */
actual val moneyFormatter: MoneyFormatter = AndroidMoneyFormatter()

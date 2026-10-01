package com.yawnandpawn.app.ui.format

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

actual fun formatMoney(money: Money): String {
    val format = NumberFormat.getCurrencyInstance(Locale.getDefault())
    format.currency = Currency.getInstance(money.currencyCode)
    if (money.amountMicros % Money.MICROS_PER_UNIT == 0L) {
        format.minimumFractionDigits = 0
        format.maximumFractionDigits = 0
    }
    return format.format(BigDecimal.valueOf(money.amountMicros).movePointLeft(MICRO_DIGITS))
}

private const val MICRO_DIGITS = 6

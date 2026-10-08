package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.MoneyFormatter

/**
 * [MoneyFormatter] with a fixed, locale-free form: the currency code, then the amount in whole units with all 6 micro
 * digits ("USD 1.000000", "EUR -0.500000"). Every amount formatted is kept in [formatted].
 */
class FakeMoneyFormatter : MoneyFormatter {
    private val recorded = mutableListOf<Money>()

    val formatted: List<Money>
        get() = recorded.toList()

    override fun format(money: Money): String {
        recorded += money
        val sign = if (money.micros < 0) "-" else ""
        val units = money.micros / Money.MICROS_PER_UNIT
        val fraction = money.micros % Money.MICROS_PER_UNIT
        val unitsText = if (units < 0) (-units).toString() else units.toString()
        val fractionText = (if (fraction < 0) -fraction else fraction).toString().padStart(MICRO_DIGITS, '0')
        return "${money.currency} $sign$unitsText.$fractionText"
    }

    private companion object {
        const val MICRO_DIGITS = 6
    }
}

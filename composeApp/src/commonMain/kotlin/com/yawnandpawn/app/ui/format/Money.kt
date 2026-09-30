package com.yawnandpawn.app.ui.format

/**
 * An amount of money for display: [amountMicros] millionths of one unit of [currencyCode] (ISO 4217), the way
 * Google Play reports prices. Always shown through [formatMoney], never with a hard-coded currency symbol.
 */
data class Money(
    val amountMicros: Long,
    val currencyCode: String,
) {
    operator fun times(factor: Int): Money = copy(amountMicros = amountMicros * factor)

    operator fun plus(other: Money): Money {
        require(other.currencyCode == currencyCode) { "cannot add $currencyCode and ${other.currencyCode}" }
        return copy(amountMicros = amountMicros + other.amountMicros)
    }

    companion object {
        const val MICROS_PER_UNIT: Long = 1_000_000L

        /** [units] whole units of [currencyCode]. */
        fun of(
            units: Int,
            currencyCode: String,
        ): Money = Money(units * MICROS_PER_UNIT, currencyCode)
    }
}

/** [money] in the phone's locale with its currency ("$3", "3 €"); whole amounts drop the decimals. */
expect fun formatMoney(money: Money): String

/** [value] as a locale number with one decimal ("0.4", "0,4"), for averages. */
expect fun formatOneDecimal(value: Double): String

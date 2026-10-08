package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome

/**
 * An amount of money (AD-8), the only money type in the app: [micros] millionths of one unit of [currency], the way
 * Google Play reports prices (1_000_000 is one dollar, one euro or one yen), and [currency] an ISO 4217 code of 3
 * upper-case letters. Integer only, so totals never pick up floating-point error. It is shown only through a
 * [MoneyFormatter], never with a hard-coded currency symbol.
 *
 * Amounts of different currencies are never added: [plus] returns [DomainError.CurrencyMismatch], and totals are grouped
 * per currency with [totalsByCurrency]. Zero and negative amounts are allowed.
 *
 * The constructor rejects a malformed [currency] (a programming error); input from Play or storage goes through [parse].
 */
data class Money(
    val micros: Long,
    val currency: String,
) {
    init {
        require(isCurrencyCode(currency)) { "currency must be an ISO 4217 code (3 upper-case letters), was \"$currency\"" }
    }

    /** The sum with [other], or [DomainError.CurrencyMismatch] when the currencies differ. Never throws. */
    operator fun plus(other: Money): Outcome<Money, DomainError.CurrencyMismatch> =
        if (other.currency == currency) {
            Outcome.Success(copy(micros = micros + other.micros))
        } else {
            Outcome.Failure(DomainError.CurrencyMismatch(currency, other.currency))
        }

    /**
     * [factor] times this amount, for ladder approximations ("about B × N" before Play's own prices are loaded). The
     * price of a snooze always comes from its own Play product.
     */
    operator fun times(factor: Int): Money = copy(micros = micros * factor)

    companion object {
        /** Micros in one whole unit of any currency (Play's convention, whatever the currency's fraction digits). */
        const val MICROS_PER_UNIT: Long = 1_000_000L

        /** [units] whole units of [currency]. */
        fun of(
            units: Int,
            currency: String,
        ): Money = Money(units * MICROS_PER_UNIT, currency)

        /** A [Money] from untrusted input (Play, storage), or [DomainError.InvalidCurrency] for a malformed code. */
        fun parse(
            micros: Long,
            currency: String,
        ): Outcome<Money, DomainError.InvalidCurrency> {
            val valid = isCurrencyCode(currency)
            return if (valid) Outcome.Success(Money(micros, currency)) else Outcome.Failure(DomainError.InvalidCurrency(currency))
        }

        /** Whether [code] has the shape of an ISO 4217 code: exactly 3 letters A–Z. */
        fun isCurrencyCode(code: String): Boolean = code.length == CODE_LENGTH && code.all { it in 'A'..'Z' }

        private const val CODE_LENGTH = 3
    }
}

/**
 * One total per currency in [amounts], in the order each currency first appears (AD-8: totals are grouped by currency,
 * never converted). Empty for an empty list.
 */
fun totalsByCurrency(amounts: List<Money>): List<Money> {
    val totals = LinkedHashMap<String, Long>()
    amounts.forEach { totals[it.currency] = (totals[it.currency] ?: 0L) + it.micros }
    return totals.map { (currency, micros) -> Money(micros, currency) }
}

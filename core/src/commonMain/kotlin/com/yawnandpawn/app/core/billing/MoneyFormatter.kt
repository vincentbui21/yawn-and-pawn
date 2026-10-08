package com.yawnandpawn.app.core.billing

/**
 * Formats [Money] for display (AD-8), the only way the app turns an amount into text: the platform adapter uses the
 * phone's locale and the currency's own symbol and fraction digits ("$1.00", "1,00 €", "￥150"). Prices shown before a
 * purchase are Play's own `formattedPrice` (Story 4.3); this formats totals, history and approximations.
 */
fun interface MoneyFormatter {
    fun format(money: Money): String
}

/**
 * [amounts] as one formatted total per currency, in first-seen order, joined with " + " (EXPERIENCE.md "Money, mixed
 * currencies": "{amount1} + {amount2}"). Empty for an empty list; the caller shows its own "no charge" copy then.
 */
fun MoneyFormatter.formatTotals(amounts: List<Money>): String = totalsByCurrency(amounts).joinToString(TOTALS_SEPARATOR) { format(it) }

/** Between the per-currency totals of [formatTotals]. */
const val TOTALS_SEPARATOR = " + "

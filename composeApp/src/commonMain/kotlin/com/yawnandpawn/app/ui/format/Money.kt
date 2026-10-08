package com.yawnandpawn.app.ui.format

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.MoneyFormatter
import com.yawnandpawn.app.core.billing.formatTotals

/**
 * The phone's [MoneyFormatter] (AD-8): the only way the UI turns a [Money] into text, in the phone's locale with the
 * currency's own symbol and fraction digits. Never a hard-coded currency symbol.
 */
expect val moneyFormatter: MoneyFormatter

/** [money] in the phone's locale with its currency ("$3.00", "3,00 €", "￥300"). */
fun formatMoney(money: Money): String = moneyFormatter.format(money)

/** One total per currency in [amounts], joined with " + " ("$3.00 + €2.00"); empty for an empty list. */
fun formatMoney(amounts: List<Money>): String = moneyFormatter.formatTotals(amounts)

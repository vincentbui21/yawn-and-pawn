package com.yawnandpawn.app.ui.purchases

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.NavRow
import com.yawnandpawn.app.ui.components.SubScreen
import com.yawnandpawn.app.ui.format.DateStyle
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.format.formatDate
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.editor_back
import com.yawnandpawn.app.ui.resources.problem_title
import com.yawnandpawn.app.ui.resources.purchase_history_empty
import com.yawnandpawn.app.ui.resources.purchase_history_title
import com.yawnandpawn.app.ui.resources.purchase_row_title
import com.yawnandpawn.app.ui.resources.purchase_snooze_number
import com.yawnandpawn.app.ui.resources.purchase_stranded
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.TABULAR_FIGURES
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource

/** One charge (FR-PRG-4): when, for which alarm, which snooze of the session and its localized price. */
data class Purchase(
    val date: LocalDate,
    val alarmTime: LocalTime,
    val snoozeNumber: Int,
    val price: Money,
    /** Paid but not used: refunded automatically by Google. */
    val stranded: Boolean = false,
)

/** What Purchase history renders: every charge, newest first. */
data class PurchaseHistoryUiState(
    val purchases: List<Purchase> = emptyList(),
)

/**
 * Purchase history, stateless (pushed from Progress and from Payments & refunds): one card per month of `purchase-row`s,
 * newest first, each with date and alarm, "Snooze 2" (or "Not used, refunded automatically by Google") and the price
 * right-aligned in `text` (never accent, green or red). Empty: "No snoozes paid. Keep it that way." A "Problem with a
 * charge?" row follows.
 */
@Composable
fun PurchaseHistoryScreen(
    state: PurchaseHistoryUiState,
    is24Hour: Boolean,
    onBack: () -> Unit,
    onProblemWithCharge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SubScreen(
        title = stringResource(Res.string.purchase_history_title),
        backContentDescription = stringResource(Res.string.editor_back),
        onBack = onBack,
        modifier = modifier,
    ) {
        if (state.purchases.isEmpty()) {
            GroupCard {
                Text(
                    text = stringResource(Res.string.purchase_history_empty),
                    modifier = Modifier.padding(PpsTheme.spacing.cardPadding),
                    style = PpsTheme.typography.body,
                    color = PpsTheme.colors.text,
                )
            }
        } else {
            state.purchases
                .groupBy { LocalDate(it.date.year, it.date.month, 1) }
                .forEach { (month, purchases) ->
                    GroupCard(title = formatDate(month, DateStyle.MonthYear)) {
                        purchases.forEachIndexed { index, purchase ->
                            if (index > 0) GroupDivider()
                            PurchaseRow(purchase = purchase, is24Hour = is24Hour)
                        }
                    }
                }
        }
        GroupCard {
            NavRow(label = stringResource(Res.string.problem_title), onClick = onProblemWithCharge)
        }
    }
}

/** `purchase-row`: 64 dp, date and alarm in `body`, the snooze number (or the stranded note) in `caption`, the price. */
@Composable
private fun PurchaseRow(
    purchase: Purchase,
    is24Hour: Boolean,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_HEIGHT)
                .semantics(mergeDescendants = true) { }
                .padding(horizontal = spacing.cardPadding, vertical = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = spacing.space3)) {
            Text(
                text =
                    stringResource(
                        Res.string.purchase_row_title,
                        formatDate(purchase.date, DateStyle.WeekdayDayMonth),
                        formatClockTime(purchase.alarmTime, is24Hour),
                    ),
                style = PpsTheme.typography.body,
                color = colors.text,
            )
            Text(
                text =
                    if (purchase.stranded) {
                        stringResource(Res.string.purchase_stranded)
                    } else {
                        stringResource(Res.string.purchase_snooze_number, purchase.snoozeNumber)
                    },
                style = PpsTheme.typography.caption,
                color = colors.textSecondary,
            )
        }
        Text(
            text = formatMoney(purchase.price),
            style = PpsTheme.typography.body.copy(fontFeatureSettings = TABULAR_FIGURES),
            color = colors.text,
        )
    }
}

/** DESIGN.md `purchase-row.height`. */
private val ROW_HEIGHT = 64.dp

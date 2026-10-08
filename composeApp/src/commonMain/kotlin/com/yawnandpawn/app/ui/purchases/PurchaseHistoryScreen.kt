package com.yawnandpawn.app.ui.purchases

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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
import kotlin.math.roundToInt

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

/**
 * `purchase-row`: 64 dp, date and alarm in `body`, the snooze number (or the stranded note) in `caption`, the price on
 * the right. The date and alarm line never wraps beside the price: when it does not fit there (large font scales, long
 * local prices), it takes the full width and the price moves down beside the caption ([PurchaseRowLayout]). The alarm
 * time keeps its AM/PM marker on its line.
 */
@Composable
private fun PurchaseRow(
    purchase: Purchase,
    is24Hour: Boolean,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    PurchaseRowLayout(
        gap = spacing.space3,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_HEIGHT)
                .semantics(mergeDescendants = true) { }
                .padding(horizontal = spacing.cardPadding, vertical = spacing.space2),
        title = {
            Text(
                text =
                    stringResource(
                        Res.string.purchase_row_title,
                        formatDate(purchase.date, DateStyle.WeekdayDayMonth),
                        formatClockTime(purchase.alarmTime, is24Hour).replace(' ', NO_BREAK_SPACE),
                    ),
                style = PpsTheme.typography.body,
                color = colors.text,
            )
        },
        detail = {
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
        },
        price = {
            Text(
                text = formatMoney(purchase.price),
                style = PpsTheme.typography.body.copy(fontFeatureSettings = TABULAR_FIGURES),
                color = colors.text,
                maxLines = 1,
                softWrap = false,
            )
        },
    )
}

/**
 * Lays out a purchase row: [title] over [detail] on the left and [price] on the right, [gap] apart, all centred
 * vertically like a `Row`. When [title] would not fit on one line beside the price, it takes the full width and the
 * price is centred on [detail] instead, so the title never wraps for the price's sake.
 */
@Composable
private fun PurchaseRowLayout(
    gap: Dp,
    title: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    price: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(title, detail, price), modifier = modifier) { (titles, details, prices), constraints ->
        val width = constraints.maxWidth
        val pricePlaceable = prices.single().measure(Constraints(maxWidth = width))
        val beside = (width - pricePlaceable.width - gap.roundToPx()).coerceAtLeast(0)
        val titleFits = titles.single().maxIntrinsicWidth(Constraints.Infinity) <= beside
        val titlePlaceable = titles.single().measure(Constraints(maxWidth = if (titleFits) beside else width))
        val detailPlaceable = details.single().measure(Constraints(maxWidth = beside))
        val content =
            if (titleFits) {
                maxOf(titlePlaceable.height + detailPlaceable.height, pricePlaceable.height)
            } else {
                titlePlaceable.height + maxOf(detailPlaceable.height, pricePlaceable.height)
            }
        val height = content.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            val priceX = width - pricePlaceable.width
            if (titleFits) {
                val top = centred(titlePlaceable.height + detailPlaceable.height, height)
                titlePlaceable.place(0, top)
                detailPlaceable.place(0, top + titlePlaceable.height)
                pricePlaceable.place(priceX, centred(pricePlaceable.height, height))
            } else {
                val top = centred(content, height)
                val lower = maxOf(detailPlaceable.height, pricePlaceable.height)
                titlePlaceable.place(0, top)
                detailPlaceable.place(0, top + titlePlaceable.height + centred(detailPlaceable.height, lower))
                pricePlaceable.place(priceX, top + titlePlaceable.height + centred(pricePlaceable.height, lower))
            }
        }
    }
}

/** Where a child of [size] starts to sit centred in [space], rounded like `Alignment.CenterVertically`. */
private fun centred(
    size: Int,
    space: Int,
): Int = ((space - size) / 2f).roundToInt()

/** Keeps "7:30 AM" on one line. */
private const val NO_BREAK_SPACE = '\u00A0'

/** DESIGN.md `purchase-row.height`. */
private val ROW_HEIGHT = 64.dp

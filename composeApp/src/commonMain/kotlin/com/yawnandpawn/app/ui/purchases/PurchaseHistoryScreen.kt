package com.yawnandpawn.app.ui.purchases

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.NavRow
import com.yawnandpawn.app.ui.components.PpsTextButton
import com.yawnandpawn.app.ui.components.SubScreen
import com.yawnandpawn.app.ui.format.DateStyle
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.format.formatDate
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.editor_back
import com.yawnandpawn.app.ui.resources.home_try_again
import com.yawnandpawn.app.ui.resources.problem_title
import com.yawnandpawn.app.ui.resources.purchase_history_empty
import com.yawnandpawn.app.ui.resources.purchase_history_load_failed
import com.yawnandpawn.app.ui.resources.purchase_history_title
import com.yawnandpawn.app.ui.resources.purchase_row_title
import com.yawnandpawn.app.ui.resources.purchase_snooze_number
import com.yawnandpawn.app.ui.resources.purchase_stranded
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.TABULAR_FIGURES
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Purchase history, stateless (pushed from the You tab's Money card, later also from Progress and Payments & refunds):
 * one card per month of `purchase-row`s, newest first, each with date and alarm, "Snooze 2" (or "Not used, refunded
 * automatically by Google") and the price right-aligned in `text` (never accent, green or red), or no price when the
 * amount is not known. Each month's title shows what was paid that month, one total per currency. Empty: "No snoozes
 * paid. Keep it that way." Loading: a `skeleton` after 300 ms. A failed read: "Couldn't load your purchases." with
 * "Try again". A "Problem with a charge?" row follows when [showProblemWithCharge] (Story 4.17 builds its screen).
 */
@Composable
fun PurchaseHistoryScreen(
    state: PurchaseHistoryUiState,
    is24Hour: Boolean,
    onBack: () -> Unit,
    onProblemWithCharge: () -> Unit,
    modifier: Modifier = Modifier,
    onIntent: (PurchaseHistoryIntent) -> Unit = {},
    showProblemWithCharge: Boolean = true,
) {
    SubScreen(
        title = stringResource(Res.string.purchase_history_title),
        backContentDescription = stringResource(Res.string.editor_back),
        onBack = onBack,
        modifier = modifier,
    ) {
        when {
            state.loadFailed -> LoadFailed(onRetry = { onIntent(PurchaseHistoryIntent.RetryLoad) })
            state.loading -> DelayedSkeleton()
            state.purchases.isEmpty() -> Empty()
            else -> monthsOf(state.purchases).forEach { month -> MonthCard(month = month, is24Hour = is24Hour) }
        }
        if (showProblemWithCharge && !state.loading) {
            GroupCard {
                NavRow(label = stringResource(Res.string.problem_title), onClick = onProblemWithCharge)
            }
        }
    }
}

@Composable
private fun Empty() {
    GroupCard {
        Text(
            text = stringResource(Res.string.purchase_history_empty),
            modifier = Modifier.padding(PpsTheme.spacing.cardPadding),
            style = PpsTheme.typography.body,
            color = PpsTheme.colors.text,
        )
    }
}

/** The records could not be read: "Couldn't load your purchases." with `button-text` "Try again" (never the empty state). */
@Composable
private fun LoadFailed(onRetry: () -> Unit) {
    val spacing = PpsTheme.spacing
    GroupCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = spacing.cardPadding, end = spacing.cardPadding, top = spacing.cardPadding),
        ) {
            Text(
                text = stringResource(Res.string.purchase_history_load_failed),
                style = PpsTheme.typography.body,
                color = PpsTheme.colors.text,
            )
            PpsTextButton(
                text = stringResource(Res.string.home_try_again),
                onClick = onRetry,
                modifier = Modifier.padding(vertical = spacing.space1),
            )
        }
    }
}

/** A month card: the month and its total above the card, then its rows. */
@Composable
private fun MonthCard(
    month: PurchaseMonth,
    is24Hour: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        MonthTitle(title = formatDate(month.month, DateStyle.MonthYear), total = month.paid.takeIf { it.isNotEmpty() }?.let(::formatMoney))
        GroupCard {
            month.purchases.forEachIndexed { index, purchase ->
                if (index > 0) GroupDivider()
                PurchaseRow(purchase = purchase, is24Hour = is24Hour)
            }
        }
    }
}

/**
 * The month (`label`, `text-secondary`) with the month's [total] right-aligned in `text` (money is neutral), tabular
 * figures; one heading for TalkBack ("September 2026, $11.00"). No total when nothing with a known amount was paid.
 * The month never wraps for the total's sake: when both do not fit on one line (large font scales, several
 * currencies), the total moves under the month, still right-aligned.
 */
@Composable
private fun MonthTitle(
    title: String,
    total: String?,
) {
    val spacing = PpsTheme.spacing
    val typography = PpsTheme.typography
    val colors = PpsTheme.colors
    val gap = spacing.space3
    Layout(
        contents =
            listOf(
                { Text(text = title, style = typography.label, color = colors.textSecondary) },
                {
                    if (total != null) {
                        Text(
                            text = total,
                            style = typography.label.copy(fontFeatureSettings = TABULAR_FIGURES),
                            color = colors.text,
                            textAlign = TextAlign.End,
                        )
                    }
                },
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = spacing.cardPadding, end = spacing.cardPadding, bottom = spacing.space2)
                .semantics(mergeDescendants = true) { heading() },
    ) { (titles, totals), constraints ->
        val width = constraints.maxWidth
        val totalPlaceable = totals.firstOrNull()?.measure(Constraints(maxWidth = width))
        val beside = (width - (totalPlaceable?.let { it.width + gap.roundToPx() } ?: 0)).coerceAtLeast(0)
        val fits = totalPlaceable == null || titles.single().maxIntrinsicWidth(Constraints.Infinity) <= beside
        val titlePlaceable = titles.single().measure(Constraints(maxWidth = if (fits) beside else width))
        val totalHeight = totalPlaceable?.height ?: 0
        val height = if (fits) maxOf(titlePlaceable.height, totalHeight) else titlePlaceable.height + totalHeight
        layout(width, height) {
            if (fits) {
                // Bottoms aligned, like the rows' text above the card.
                titlePlaceable.place(0, height - titlePlaceable.height)
                totalPlaceable?.place(width - totalPlaceable.width, height - totalPlaceable.height)
            } else {
                titlePlaceable.place(0, 0)
                totalPlaceable?.place(width - totalPlaceable.width, titlePlaceable.height)
            }
        }
    }
}

/**
 * The `skeleton` (`surface-variant` blocks, `rounded.sm`, no shimmer) of a card of rows, shown only once loading took
 * [SKELETON_DELAY_MILLIS], so a quick read never flashes it. Decorative: TalkBack skips it.
 */
@Composable
private fun DelayedSkeleton() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SKELETON_DELAY_MILLIS)
        visible = true
    }
    if (!visible) return
    val spacing = PpsTheme.spacing
    GroupCard(modifier = Modifier.testTag(PURCHASE_HISTORY_SKELETON_TAG).clearAndSetSemantics { }) {
        repeat(SKELETON_ROWS) { index ->
            if (index > 0) GroupDivider()
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = ROW_HEIGHT)
                        .padding(horizontal = spacing.cardPadding, vertical = spacing.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SkeletonBlock(Modifier.fillMaxWidth(TITLE_BLOCK_FRACTION).height(spacing.space4))
                    Spacer(Modifier.height(spacing.space2))
                    SkeletonBlock(Modifier.fillMaxWidth(DETAIL_BLOCK_FRACTION).height(spacing.space3))
                }
                SkeletonBlock(Modifier.width(spacing.space8).height(spacing.space4))
            }
        }
    }
}

@Composable
private fun SkeletonBlock(modifier: Modifier) {
    Box(modifier = modifier.background(PpsTheme.colors.surfaceVariant, PpsTheme.shapes.sm))
}

/**
 * `purchase-row`: 64 dp, date and alarm in `body`, the snooze number (or the stranded note) in `caption`, the price on
 * the right (none when not known). The date and alarm line never wraps beside the price: when it does not fit there
 * (large font scales, long local prices), it takes the full width and the price moves down beside the caption
 * ([PurchaseRowLayout]). The alarm is its label, or its time (which keeps its AM/PM marker on its line); with neither,
 * only the date shows. TalkBack reads the row as one item.
 */
@Composable
private fun PurchaseRow(
    purchase: Purchase,
    is24Hour: Boolean,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val date = formatDate(purchase.date, DateStyle.WeekdayDayMonth)
    val alarm = purchase.alarmLabel ?: purchase.alarmTime?.let { formatClockTime(it, is24Hour).replace(' ', NO_BREAK_SPACE) }
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
                text = if (alarm == null) date else stringResource(Res.string.purchase_row_title, date, alarm),
                style = PpsTheme.typography.body,
                color = colors.text,
            )
        },
        detail = {
            val number = purchase.snoozeNumber
            val detail =
                when {
                    purchase.stranded -> stringResource(Res.string.purchase_stranded)
                    number != null -> stringResource(Res.string.purchase_snooze_number, number)
                    else -> null
                }
            if (detail == null) {
                Spacer(Modifier)
            } else {
                Text(text = detail, style = PpsTheme.typography.caption, color = colors.textSecondary)
            }
        },
        price = {
            val price = purchase.price
            if (price == null) {
                Spacer(Modifier)
            } else {
                Text(
                    text = formatMoney(price),
                    style = PpsTheme.typography.body.copy(fontFeatureSettings = TABULAR_FIGURES),
                    color = colors.text,
                    maxLines = 1,
                    softWrap = false,
                )
            }
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

/** EXPERIENCE.md App screens, Loading: the skeleton shows only after this long. */
internal const val SKELETON_DELAY_MILLIS = 300L

/** The test tag of the loading skeleton. */
const val PURCHASE_HISTORY_SKELETON_TAG = "purchase-history-skeleton"

private const val SKELETON_ROWS = 3
private const val TITLE_BLOCK_FRACTION = 0.55f
private const val DETAIL_BLOCK_FRACTION = 0.3f

package com.yawnandpawn.app.ui.payments

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.yawnandpawn.app.ui.components.AppSnackbar
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.NavRow
import com.yawnandpawn.app.ui.components.NoteInline
import com.yawnandpawn.app.ui.components.SubScreen
import com.yawnandpawn.app.ui.components.TextCard
import com.yawnandpawn.app.ui.format.Money
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.disclosure_escape_body
import com.yawnandpawn.app.ui.resources.disclosure_ringing_body
import com.yawnandpawn.app.ui.resources.disclosure_usable_body
import com.yawnandpawn.app.ui.resources.editor_back
import com.yawnandpawn.app.ui.resources.payments_cap_body
import com.yawnandpawn.app.ui.resources.payments_fee_body
import com.yawnandpawn.app.ui.resources.payments_lock_body
import com.yawnandpawn.app.ui.resources.payments_pending_body
import com.yawnandpawn.app.ui.resources.payments_self_refund_body
import com.yawnandpawn.app.ui.resources.payments_unused_body
import com.yawnandpawn.app.ui.resources.problem_email_support
import com.yawnandpawn.app.ui.resources.problem_open_play
import com.yawnandpawn.app.ui.resources.problem_pending_body
import com.yawnandpawn.app.ui.resources.problem_title
import com.yawnandpawn.app.ui.resources.purchase_auth_tip
import com.yawnandpawn.app.ui.resources.purchase_history_title
import com.yawnandpawn.app.ui.resources.settings_no_browser
import com.yawnandpawn.app.ui.resources.settings_payments
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.stringResource

/**
 * "How payments & refunds work" (FR-SET-3), stateless: the fee rules with the morning's [priceCap], pending and unused
 * payments and Google's self-refund, then the alarm behaviour disclosure verbatim and the purchase authentication tip,
 * then links to "Problem with a charge?" and Purchase history. Long-form copy, one paragraph per string.
 */
@Composable
fun PaymentsScreen(
    priceCap: Money,
    onIntent: (PaymentsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    SubScreen(
        title = stringResource(Res.string.settings_payments),
        backContentDescription = stringResource(Res.string.editor_back),
        onBack = { onIntent(PaymentsIntent.Back) },
        modifier = modifier,
    ) {
        TextCard(
            paragraphs =
                listOf(
                    stringResource(Res.string.payments_fee_body),
                    stringResource(Res.string.payments_cap_body, formatMoney(priceCap)),
                    stringResource(Res.string.payments_lock_body),
                ),
        )
        TextCard(
            paragraphs =
                listOf(
                    stringResource(Res.string.payments_pending_body),
                    stringResource(Res.string.payments_unused_body),
                    stringResource(Res.string.payments_self_refund_body),
                ),
        )
        TextCard(
            paragraphs =
                listOf(
                    stringResource(Res.string.disclosure_ringing_body),
                    stringResource(Res.string.disclosure_usable_body),
                    stringResource(Res.string.disclosure_escape_body),
                ),
        )
        NoteInline(
            text = stringResource(Res.string.purchase_auth_tip),
            modifier = Modifier.padding(horizontal = PpsTheme.spacing.cardPadding),
        )
        GroupCard {
            NavRow(label = stringResource(Res.string.problem_title), onClick = { onIntent(PaymentsIntent.ProblemWithChargeClicked) })
            GroupDivider()
            NavRow(label = stringResource(Res.string.purchase_history_title), onClick = { onIntent(PaymentsIntent.PurchaseHistoryClicked) })
        }
    }
}

/**
 * "Problem with a charge?" (FR-SET-5), stateless: Google's 48-hour self-refund, automatic refunds of unused payments and
 * how pending payments charge, then "Open Google Play order history" and "Email support" (pre-filled with the order).
 * With [noBrowser] the link had no app to open it: the "No browser found." snackbar.
 */
@Composable
fun ProblemWithChargeScreen(
    onIntent: (PaymentsIntent) -> Unit,
    modifier: Modifier = Modifier,
    noBrowser: Boolean = false,
) {
    Box(modifier = modifier.fillMaxSize()) {
        SubScreen(
            title = stringResource(Res.string.problem_title),
            backContentDescription = stringResource(Res.string.editor_back),
            onBack = { onIntent(PaymentsIntent.Back) },
        ) {
            TextCard(
                paragraphs =
                    listOf(
                        stringResource(Res.string.payments_self_refund_body),
                        stringResource(Res.string.payments_unused_body),
                        stringResource(Res.string.problem_pending_body),
                    ),
            )
            GroupCard {
                NavRow(label = stringResource(Res.string.problem_open_play), onClick = { onIntent(PaymentsIntent.OpenOrderHistoryClicked) })
                GroupDivider()
                NavRow(label = stringResource(Res.string.problem_email_support), onClick = { onIntent(PaymentsIntent.EmailSupportClicked) })
            }
        }
        if (noBrowser) AppSnackbar(text = stringResource(Res.string.settings_no_browser), modifier = Modifier.align(Alignment.BottomCenter))
    }
}

package com.yawnandpawn.app.ui.payments

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import com.yawnandpawn.app.ui.components.AppSnackbar
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.PpsBackground
import com.yawnandpawn.app.ui.components.PpsFilledButton
import com.yawnandpawn.app.ui.components.PpsTextButton
import com.yawnandpawn.app.ui.components.PpsTopAppBar
import com.yawnandpawn.app.ui.components.RadioRow
import com.yawnandpawn.app.ui.components.ScreenColumn
import com.yawnandpawn.app.ui.components.subScreenTransition
import com.yawnandpawn.app.ui.format.DateStyle
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.format.formatDate
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.editor_back
import com.yawnandpawn.app.ui.resources.problem_address_copied
import com.yawnandpawn.app.ui.resources.problem_charge_detail
import com.yawnandpawn.app.ui.resources.problem_copy_address
import com.yawnandpawn.app.ui.resources.problem_email_support
import com.yawnandpawn.app.ui.resources.problem_no_email_app
import com.yawnandpawn.app.ui.resources.problem_pick_charge
import com.yawnandpawn.app.ui.resources.purchase_row_title
import com.yawnandpawn.app.ui.resources.purchase_snooze_number
import com.yawnandpawn.app.ui.resources.purchase_stranded
import com.yawnandpawn.app.ui.resources.settings_no_browser
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.stringResource

/**
 * "Problem with a charge?" with its "Email support" picker (Story 4.17), stateless: the screen, or, while
 * [ProblemWithChargeUiState.picking], the picker sliding in over it (the 250 ms sub-screen slide; instant with reduced
 * motion). The state's snackbar, and the no-email-app note, show on whichever is in front.
 */
@Composable
fun ProblemWithChargeFlow(
    state: ProblemWithChargeUiState,
    is24Hour: Boolean,
    onIntent: (PaymentsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar =
        when (state.snackbar) {
            ChargeSnackbar.NoBrowser -> stringResource(Res.string.settings_no_browser)
            ChargeSnackbar.AddressCopied -> stringResource(Res.string.problem_address_copied)
            null -> null
        }
    val noEmailAppAddress = state.supportEmail.takeIf { state.noEmailApp }
    AnimatedContent(
        targetState = state.picking,
        modifier = modifier,
        transitionSpec = { subScreenTransition(forward = targetState) },
        label = "problem with a charge pane",
    ) { picking ->
        if (picking) {
            SupportChargePicker(
                charges = state.charges,
                selected = state.selected,
                is24Hour = is24Hour,
                onIntent = onIntent,
                snackbar = snackbar,
                noEmailAppAddress = noEmailAppAddress,
            )
        } else {
            ProblemWithChargeMain(onIntent = onIntent, snackbar = snackbar, noEmailAppAddress = noEmailAppAddress)
        }
    }
}

/**
 * No email app could open the support email (review M1): a glass note, announced politely, saying so with [address]
 * on its own line, which wraps only after "@" or "." ([breakableAddress]) and TalkBack reads as the plain address,
 * and `button-text` "Copy address" ([onCopy]). It stays until the user moves on, so the address stays usable.
 */
@Composable
internal fun NoEmailAppNote(
    address: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PpsTheme.spacing
    val colors = PpsTheme.colors
    GroupCard(modifier = modifier.testTag(NO_EMAIL_APP_NOTE_TAG)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = spacing.cardPadding, end = spacing.cardPadding, top = spacing.cardPadding),
        ) {
            Column(modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(text = stringResource(Res.string.problem_no_email_app), style = PpsTheme.typography.body, color = colors.text)
                Text(
                    text = breakableAddress(address),
                    modifier = Modifier.clearAndSetSemantics { text = AnnotatedString(address) },
                    style = PpsTheme.typography.body,
                    color = colors.text,
                )
            }
            PpsTextButton(
                text = stringResource(Res.string.problem_copy_address),
                onClick = onCopy,
                modifier = Modifier.padding(vertical = spacing.space1),
            )
        }
    }
}

/** Test tag of the no-email-app note. */
const val NO_EMAIL_APP_NOTE_TAG = "problem_no_email_app_note"

/**
 * The "Email support" picker, stateless: a `card-group` "Which charge?" of radio rows, one per charge in [charges]
 * (newest first), each with its date and time over its price and status (as Purchase history words it; no price when
 * it is not known), [selected] checked; then `button-filled` "Email support" in its own bottom area, never scrolled away.
 * The [snackbar], and the no-email-app note when [noEmailAppAddress] is set, show above the button, in their own area
 * (never over the button or a row). TalkBack reads each row as one radio button with its state.
 */
@Composable
fun SupportChargePicker(
    charges: List<ChargeOption>,
    selected: ChargeOption?,
    is24Hour: Boolean,
    onIntent: (PaymentsIntent) -> Unit,
    modifier: Modifier = Modifier,
    snackbar: String? = null,
    noEmailAppAddress: String? = null,
) {
    val spacing = PpsTheme.spacing
    PpsBackground(modifier = modifier) {
        // The insets are taken once here, so the list and the snackbar below do not add the navigation bar again.
        Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            PpsTopAppBar(
                title = stringResource(Res.string.problem_email_support),
                backContentDescription = stringResource(Res.string.editor_back),
                onBack = { onIntent(PaymentsIntent.Back) },
            )
            ScreenColumn(modifier = Modifier.weight(1f), bottomClearance = spacing.space3) {
                GroupCard(title = stringResource(Res.string.problem_pick_charge)) {
                    Column(modifier = Modifier.selectableGroup()) {
                        charges.forEachIndexed { index, charge ->
                            if (index > 0) GroupDivider()
                            RadioRow(
                                label = chargeTitle(charge, is24Hour),
                                subtitle = chargeDetail(charge),
                                selected = charge.key == selected?.key,
                                onSelect = { onIntent(PaymentsIntent.ChargePicked(charge.key)) },
                            )
                        }
                    }
                }
            }
            if (noEmailAppAddress != null) {
                NoEmailAppNote(
                    address = noEmailAppAddress,
                    onCopy = { onIntent(PaymentsIntent.CopyAddressClicked) },
                    modifier = Modifier.padding(start = spacing.screenMargin, end = spacing.screenMargin, top = spacing.space3),
                )
            }
            if (snackbar != null) AppSnackbar(text = snackbar)
            PpsFilledButton(
                text = stringResource(Res.string.problem_email_support),
                onClick = { onIntent(PaymentsIntent.SendEmailClicked) },
                modifier = Modifier.padding(horizontal = spacing.screenMargin, vertical = spacing.space4).fillMaxWidth(),
            )
        }
    }
}

/** "Tue, Sep 22 · 7:32 AM": the day and time of the charge in the phone's zone. */
@Composable
private fun chargeTitle(
    charge: ChargeOption,
    is24Hour: Boolean,
): String =
    stringResource(
        Res.string.purchase_row_title,
        formatDate(charge.purchasedAt.date, DateStyle.WeekdayDayMonth),
        formatClockTime(charge.purchasedAt.time, is24Hour),
    )

/** "$2.00 · Snooze 2": the price (only when known) and the status (only when known); null with neither. */
@Composable
private fun chargeDetail(charge: ChargeOption): String? {
    val price = charge.price?.let(::formatMoney)
    val status =
        when (val known = charge.status) {
            is ChargeStatus.Snooze -> stringResource(Res.string.purchase_snooze_number, known.number)
            ChargeStatus.NotUsed -> stringResource(Res.string.purchase_stranded)
            null -> null
        }
    return when {
        price != null && status != null -> stringResource(Res.string.problem_charge_detail, price, status)
        else -> price ?: status
    }
}

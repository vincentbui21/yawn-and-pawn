package com.yawnandpawn.app.ui.you

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.yawnandpawn.app.ui.components.AppSnackbar
import com.yawnandpawn.app.ui.components.ConfirmDialog
import com.yawnandpawn.app.ui.components.GroupCardOf
import com.yawnandpawn.app.ui.components.LocalNavBarClearance
import com.yawnandpawn.app.ui.components.NavRow
import com.yawnandpawn.app.ui.components.TabScreen
import com.yawnandpawn.app.ui.components.rowIf
import com.yawnandpawn.app.ui.payments.NoEmailAppNote
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.nav_you
import com.yawnandpawn.app.ui.resources.problem_address_copied
import com.yawnandpawn.app.ui.resources.problem_email_support
import com.yawnandpawn.app.ui.resources.purchase_history_title
import com.yawnandpawn.app.ui.resources.settings_delete_all
import com.yawnandpawn.app.ui.resources.settings_delete_body
import com.yawnandpawn.app.ui.resources.settings_delete_confirm
import com.yawnandpawn.app.ui.resources.settings_delete_keep
import com.yawnandpawn.app.ui.resources.settings_delete_title
import com.yawnandpawn.app.ui.resources.settings_no_browser
import com.yawnandpawn.app.ui.resources.settings_payments
import com.yawnandpawn.app.ui.resources.settings_privacy
import com.yawnandpawn.app.ui.resources.settings_support
import com.yawnandpawn.app.ui.resources.settings_terms
import com.yawnandpawn.app.ui.resources.you_about
import com.yawnandpawn.app.ui.resources.you_help
import com.yawnandpawn.app.ui.resources.you_money
import com.yawnandpawn.app.ui.resources.you_privacy_data
import com.yawnandpawn.app.ui.resources.you_version
import com.yawnandpawn.app.ui.theme.PpsTheme
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.stringResource

/** What the You tab renders. */
data class YouUiState(
    /** The app's version name for the About row ("0.1.0"). */
    val appVersion: String,
    val showDeleteDialog: Boolean = false,
    /** A link had no app to open it: "No browser found.", for 4 s. */
    val noBrowser: Boolean = false,
    /**
     * No email app could open the support email (Story 5.8): the note "No email app found. Write to:" with this address
     * and "Copy address" shows under the cards until an email opens or the user moves on. Null when not shown.
     */
    val noEmailAppAddress: String? = null,
    /** "Address copied.", for 4 s (only where the phone shows no confirmation of its own, before Android 13). */
    val addressCopied: Boolean = false,
)

/** Everything the user can do on the You tab. */
sealed interface YouIntent {
    data object PurchaseHistoryClicked : YouIntent

    data object PaymentsClicked : YouIntent

    data object PrivacyClicked : YouIntent

    data object DeleteAllClicked : YouIntent

    data object DeleteConfirmed : YouIntent

    data object DeleteCancelled : YouIntent

    data object SupportClicked : YouIntent

    /** "Email support" (Story 5.8): a new email to the support address in the user's email app. */
    data object EmailSupportClicked : YouIntent

    /** "Copy address" in the no-email-app note: the support address to the clipboard. */
    data object CopyAddressClicked : YouIntent

    data object TermsClicked : YouIntent

    data object AboutClicked : YouIntent
}

/**
 * The rows of the You tab. A screen shows the rows it is given; production leaves out the rows whose stories are not
 * built yet, so no row leads nowhere (previews show them all).
 */
enum class YouRow { PurchaseHistory, Payments, Privacy, DeleteAll, Support, EmailSupport, Terms, About }

/**
 * The You tab (owner decision 2026-10-01, feedback item 26): a personal page with no sign-in (no account, no backend,
 * NFR-4), in grouped `card-group`s: Money (Purchase history, How payments & refunds work), Privacy and your data
 * (Privacy policy, Delete all data with its dialog), Help (Support, Email support, Terms, About with the version). These
 * rows moved here from Settings, which keeps only app behaviour. Only the [rows] given show (all by default); a card
 * with none of its rows is left out. The no-email-app note follows the cards (never over a row); the snackbar sits above
 * the nav capsule.
 */
@Composable
fun YouScreen(
    state: YouUiState,
    onIntent: (YouIntent) -> Unit,
    modifier: Modifier = Modifier,
    rows: Set<YouRow> = YouRow.entries.toSet(),
) {
    Box(modifier = modifier.fillMaxSize()) {
        TabScreen(
            title = stringResource(Res.string.nav_you),
            overlay = { YouSnackbar(state) },
        ) {
            MoneyCard(onIntent = onIntent, rows = rows)
            PrivacyCard(onIntent = onIntent, rows = rows)
            HelpCard(appVersion = state.appVersion, onIntent = onIntent, rows = rows)
            state.noEmailAppAddress?.let { address ->
                NoEmailAppNote(
                    address = address,
                    onCopy = { onIntent(YouIntent.CopyAddressClicked) },
                    modifier = Modifier.revealAboveNavBar(key = address),
                )
            }
        }
    }
    if (state.showDeleteDialog) {
        ConfirmDialog(
            title = stringResource(Res.string.settings_delete_title),
            body = stringResource(Res.string.settings_delete_body),
            confirmText = stringResource(Res.string.settings_delete_confirm),
            safeText = stringResource(Res.string.settings_delete_keep),
            onConfirm = { onIntent(YouIntent.DeleteConfirmed) },
            onSafe = { onIntent(YouIntent.DeleteCancelled) },
            destructive = true,
        )
    }
}

/**
 * Scrolls this (the no-email-app note under the cards, often below the fold) into view once it appears for [key], with
 * the column's bottom clearance under it, so "Copy address" never ends up under the floating nav capsule (review fix 4).
 */
@Composable
private fun Modifier.revealAboveNavBar(key: Any): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val below = with(LocalDensity.current) { (LocalNavBarClearance.current + PpsTheme.spacing.space6).toPx() }
    LaunchedEffect(key) {
        val measured = snapshotFlow { size }.first { it != IntSize.Zero }
        requester.bringIntoView(Rect(0f, 0f, measured.width.toFloat(), measured.height + below))
    }
    return bringIntoViewRequester(requester).onSizeChanged { size = it }
}

/** The snackbar of [state], if any: "No browser found." or "Address copied.". */
@Composable
private fun YouSnackbar(state: YouUiState) {
    val text =
        when {
            state.noBrowser -> stringResource(Res.string.settings_no_browser)
            state.addressCopied -> stringResource(Res.string.problem_address_copied)
            else -> null
        }
    if (text != null) AppSnackbar(text = text)
}

/** Money: Purchase history and How payments & refunds work. */
@Composable
private fun MoneyCard(
    onIntent: (YouIntent) -> Unit,
    rows: Set<YouRow>,
) {
    GroupCardOf(
        title = stringResource(Res.string.you_money),
        rows =
            listOfNotNull(
                rowIf(YouRow.PurchaseHistory in rows) {
                    NavRow(
                        label = stringResource(Res.string.purchase_history_title),
                        onClick = { onIntent(YouIntent.PurchaseHistoryClicked) },
                    )
                },
                rowIf(YouRow.Payments in rows) {
                    NavRow(label = stringResource(Res.string.settings_payments), onClick = { onIntent(YouIntent.PaymentsClicked) })
                },
            ),
    )
}

/** Privacy and your data: Privacy policy and Delete all data (in `error`, opening its dialog). */
@Composable
private fun PrivacyCard(
    onIntent: (YouIntent) -> Unit,
    rows: Set<YouRow>,
) {
    GroupCardOf(
        title = stringResource(Res.string.you_privacy_data),
        rows =
            listOfNotNull(
                rowIf(YouRow.Privacy in rows) {
                    NavRow(label = stringResource(Res.string.settings_privacy), onClick = { onIntent(YouIntent.PrivacyClicked) })
                },
                rowIf(YouRow.DeleteAll in rows) {
                    NavRow(
                        label = stringResource(Res.string.settings_delete_all),
                        onClick = { onIntent(YouIntent.DeleteAllClicked) },
                        titleColor = PpsTheme.colors.error,
                        chevron = false,
                    )
                },
            ),
    )
}

/** Help: Support, Email support, Terms and About with the version. */
@Composable
private fun HelpCard(
    appVersion: String,
    onIntent: (YouIntent) -> Unit,
    rows: Set<YouRow>,
) {
    GroupCardOf(
        title = stringResource(Res.string.you_help),
        rows =
            listOfNotNull(
                rowIf(YouRow.Support in rows) {
                    NavRow(label = stringResource(Res.string.settings_support), onClick = { onIntent(YouIntent.SupportClicked) })
                },
                rowIf(YouRow.EmailSupport in rows) {
                    NavRow(
                        label = stringResource(Res.string.problem_email_support),
                        onClick = { onIntent(YouIntent.EmailSupportClicked) },
                    )
                },
                rowIf(YouRow.Terms in rows) {
                    NavRow(label = stringResource(Res.string.settings_terms), onClick = { onIntent(YouIntent.TermsClicked) })
                },
                rowIf(YouRow.About in rows) {
                    NavRow(
                        label = stringResource(Res.string.you_about),
                        value = stringResource(Res.string.you_version, appVersion),
                        onClick = { onIntent(YouIntent.AboutClicked) },
                    )
                },
            ),
    )
}

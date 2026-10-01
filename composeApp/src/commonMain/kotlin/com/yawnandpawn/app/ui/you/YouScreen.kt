package com.yawnandpawn.app.ui.you

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yawnandpawn.app.ui.components.AppSnackbar
import com.yawnandpawn.app.ui.components.ConfirmDialog
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.NavRow
import com.yawnandpawn.app.ui.components.TabScreen
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.nav_you
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
import org.jetbrains.compose.resources.stringResource

/** What the You tab renders. */
data class YouUiState(
    /** The app's version name for the About row ("0.1.0"). */
    val appVersion: String,
    val showDeleteDialog: Boolean = false,
    /** A link had no app to open it: "No browser found.". */
    val noBrowser: Boolean = false,
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

    data object TermsClicked : YouIntent

    data object AboutClicked : YouIntent
}

/**
 * The You tab (owner decision 2026-10-01, feedback item 26): a personal page with no sign-in (no account, no backend,
 * NFR-4), in grouped `card-group`s: Money (Purchase history, How payments & refunds work), Privacy and your data
 * (Privacy policy, Delete all data with its dialog), Help (Support, Terms, About with the version). These rows moved
 * here from Settings, which keeps only app behaviour.
 */
@Composable
fun YouScreen(
    state: YouUiState,
    onIntent: (YouIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        TabScreen(
            title = stringResource(Res.string.nav_you),
            overlay = { if (state.noBrowser) AppSnackbar(text = stringResource(Res.string.settings_no_browser)) },
        ) {
            GroupCard(title = stringResource(Res.string.you_money)) {
                NavRow(label = stringResource(Res.string.purchase_history_title), onClick = { onIntent(YouIntent.PurchaseHistoryClicked) })
                GroupDivider()
                NavRow(label = stringResource(Res.string.settings_payments), onClick = { onIntent(YouIntent.PaymentsClicked) })
            }
            GroupCard(title = stringResource(Res.string.you_privacy_data)) {
                NavRow(label = stringResource(Res.string.settings_privacy), onClick = { onIntent(YouIntent.PrivacyClicked) })
                GroupDivider()
                NavRow(
                    label = stringResource(Res.string.settings_delete_all),
                    onClick = { onIntent(YouIntent.DeleteAllClicked) },
                    titleColor = PpsTheme.colors.error,
                    chevron = false,
                )
            }
            GroupCard(title = stringResource(Res.string.you_help)) {
                NavRow(label = stringResource(Res.string.settings_support), onClick = { onIntent(YouIntent.SupportClicked) })
                GroupDivider()
                NavRow(label = stringResource(Res.string.settings_terms), onClick = { onIntent(YouIntent.TermsClicked) })
                GroupDivider()
                NavRow(
                    label = stringResource(Res.string.you_about),
                    value = stringResource(Res.string.you_version, state.appVersion),
                    onClick = { onIntent(YouIntent.AboutClicked) },
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

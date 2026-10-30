package com.yawnandpawn.app.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.components.AppSnackbar
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.NavRow
import com.yawnandpawn.app.ui.components.SubScreen
import com.yawnandpawn.app.ui.payments.NoEmailAppNote
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.about_contact_support
import com.yawnandpawn.app.ui.resources.about_website
import com.yawnandpawn.app.ui.resources.app_icon
import com.yawnandpawn.app.ui.resources.app_name
import com.yawnandpawn.app.ui.resources.editor_back
import com.yawnandpawn.app.ui.resources.problem_address_copied
import com.yawnandpawn.app.ui.resources.settings_about
import com.yawnandpawn.app.ui.resources.settings_no_browser
import com.yawnandpawn.app.ui.resources.settings_privacy
import com.yawnandpawn.app.ui.resources.settings_terms
import com.yawnandpawn.app.ui.resources.symbol_open_in_new
import com.yawnandpawn.app.ui.resources.you_version
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** What About renders. */
data class AboutUiState(
    /** The app's version name ("0.1.7"), shown as "Version 0.1.7". */
    val version: String,
    /** The website as the row shows it ("yawnandpawn.com"). */
    val website: String,
    /** The support address the "Contact support" row shows and writes to. */
    val supportEmail: String,
    /** A link had no app to open it: "No browser found.", for 4 s. */
    val noBrowser: Boolean = false,
    /**
     * No email app could open the support email: the note "No email app found. Write to:" with this address and "Copy
     * address" shows under the card until an email opens. Null when not shown.
     */
    val noEmailAppAddress: String? = null,
    /** "Address copied.", for 4 s (only where the phone shows no confirmation of its own, before Android 13). */
    val addressCopied: Boolean = false,
)

/** Everything the user can do on About (Back is the route's). */
sealed interface AboutIntent {
    data object WebsiteClicked : AboutIntent

    /** "Contact support": a new email to the support address in the user's email app. */
    data object ContactSupportClicked : AboutIntent

    data object PrivacyClicked : AboutIntent

    data object TermsClicked : AboutIntent

    /** "Copy address" in the no-email-app note: the support address to the clipboard. */
    data object CopyAddressClicked : AboutIntent
}

/**
 * About (0.1.7 polish, owner decision 2026-10-30), stateless: a sub-screen with a back arrow, the app icon (84 dp,
 * `rounded.md`), "Yawn & Pawn" in `title` and "Version {v}" in `caption` / `text-secondary`, then one `card-group` of
 * link rows, each with its value and a trailing `open_in_new` (they leave the app): Website (the site's address),
 * Contact support (the support address, opens an email), Privacy policy and Terms. The no-email-app note follows the
 * card; the snackbar ("No browser found." or "Address copied.") sits at the bottom.
 */
@Composable
fun AboutScreen(
    state: AboutUiState,
    onIntent: (AboutIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        SubScreen(
            title = stringResource(Res.string.settings_about),
            backContentDescription = stringResource(Res.string.editor_back),
            onBack = onBack,
        ) {
            AppHeader(version = state.version)
            LinksCard(state = state, onIntent = onIntent)
            state.noEmailAppAddress?.let { address ->
                NoEmailAppNote(address = address, onCopy = { onIntent(AboutIntent.CopyAddressClicked) })
            }
        }
        val snackbar =
            when {
                state.noBrowser -> stringResource(Res.string.settings_no_browser)
                state.addressCopied -> stringResource(Res.string.problem_address_copied)
                else -> null
            }
        if (snackbar != null) AppSnackbar(text = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** The icon, the app's name (a heading for TalkBack) and its version, centred. */
@Composable
private fun AppHeader(version: String) {
    val spacing = PpsTheme.spacing
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.space4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.space1),
    ) {
        // Artwork: the name follows, so TalkBack skips the icon.
        Image(
            painter = painterResource(Res.drawable.app_icon),
            contentDescription = null,
            modifier = Modifier.padding(bottom = spacing.space2).size(ICON_SIZE).clip(PpsTheme.shapes.md),
        )
        Text(
            text = stringResource(Res.string.app_name),
            modifier = Modifier.semantics { heading() },
            style = PpsTheme.typography.title,
            color = PpsTheme.colors.text,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(Res.string.you_version, version),
            style = PpsTheme.typography.caption,
            color = PpsTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Website, Contact support, Privacy policy and Terms in one card. */
@Composable
private fun LinksCard(
    state: AboutUiState,
    onIntent: (AboutIntent) -> Unit,
) {
    val external = Res.drawable.symbol_open_in_new
    GroupCard {
        NavRow(
            label = stringResource(Res.string.about_website),
            value = state.website,
            onClick = { onIntent(AboutIntent.WebsiteClicked) },
            trailingIcon = external,
        )
        GroupDivider()
        NavRow(
            label = stringResource(Res.string.about_contact_support),
            value = state.supportEmail,
            onClick = { onIntent(AboutIntent.ContactSupportClicked) },
            trailingIcon = external,
        )
        GroupDivider()
        NavRow(
            label = stringResource(Res.string.settings_privacy),
            onClick = { onIntent(AboutIntent.PrivacyClicked) },
            trailingIcon = external,
        )
        GroupDivider()
        NavRow(
            label = stringResource(Res.string.settings_terms),
            onClick = { onIntent(AboutIntent.TermsClicked) },
            trailingIcon = external,
        )
    }
}

/** The icon's size on About. */
private val ICON_SIZE = 84.dp

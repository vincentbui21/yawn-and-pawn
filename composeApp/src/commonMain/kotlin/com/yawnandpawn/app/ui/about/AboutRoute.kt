package com.yawnandpawn.app.ui.about

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

/**
 * The About route (0.1.7 polish), pushed over Settings from its "About" row: [AboutViewModel] (scoped to the nav entry)
 * feeding [AboutScreen]. The back arrow calls [onBack]; the links and the support email are the ViewModel's.
 */
@Composable
fun AboutRoute(
    onBack: () -> Unit,
    viewModel: AboutViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AboutScreen(state = state, onIntent = viewModel::onIntent, onBack = onBack)
}

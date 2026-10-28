package com.yawnandpawn.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation3.runtime.NavKey
import com.yawnandpawn.app.ui.shell.AppTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.compose.koinInject

/**
 * "Skip this one" tapped on an alarm's pre-alarm notification (Story 7.11), like [AppOpenRequests]: the activity
 * [request]s the alarm id; the navigation shows "Skip your {time} alarm? This is logged." over Home once the app is past
 * the restore, session lock and onboarding screens, and lets go of it ([handled]) in every case. During a session or
 * onboarding (or with the editor open) the request is dropped, never deferred: nothing is skipped, and the notification
 * stays. One app-wide instance (a Koin single).
 */
class SkipRequests {
    private val requested = MutableStateFlow<String?>(null)

    /** The alarm id asked for and not handled yet, or null. */
    val alarmId: StateFlow<String?> = requested.asStateFlow()

    fun request(alarmId: String) {
        requested.value = alarmId
    }

    /** Lets go of the request for [alarmId]; a newer request for another alarm stays. */
    fun handled(alarmId: String) {
        requested.compareAndSet(alarmId, null)
    }
}

/**
 * The alarm whose skip Home is to ask about ([SkipRequested] on [backStack]), kept across a configuration change until
 * Home takes it (it sets the value back to null).
 */
@Composable
internal fun rememberSkipRequest(backStack: MutableList<NavKey>): MutableState<String?> {
    val request = rememberSaveable { mutableStateOf<String?>(null) }
    SkipRequested(backStack, onSkip = { request.value = it })
    return request
}

/**
 * Takes each alarm [SkipRequests] asks for on [backStack]: when the stack can show a tab ([openRequested] selects Home),
 * [onSkip] gets the alarm id, so Home asks before skipping; otherwise (the session lock, onboarding, the editor) it is
 * dropped. Call it only once the stack follows the session lock and onboarding (after `shownStack`), like
 * [OpenRequestedTab].
 */
@Composable
internal fun SkipRequested(
    backStack: MutableList<NavKey>,
    onSkip: (String) -> Unit,
) {
    val requests = koinInject<SkipRequests>()
    val requested by requests.alarmId.collectAsState()
    LaunchedEffect(requested) {
        requested?.let { alarmId ->
            if (backStack.openRequested(AppTab.Alarms)) onSkip(alarmId)
            requests.handled(alarmId)
        }
    }
}

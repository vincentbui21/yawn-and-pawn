package com.yawnandpawn.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavKey
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.onboarding.OnboardingRepository
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

/** What the app shows for the onboarding gate (Story 5.10). */
internal enum class OnboardingGate {
    /** The stored progress is not read yet: a neutral empty screen (no Home flash on a first launch). */
    Loading,

    /** Onboarding is not completed (a first launch, or after "Delete all data"): only onboarding. */
    Required,

    /** Onboarding is completed: the app as usual. */
    Done,
}

/**
 * The onboarding gate (FR-ONB-1): [OnboardingGate.Required] until the stored progress says onboarding is completed. The
 * store emits a fresh progress when it cannot be read (logged), so an unreadable store sends the user through
 * onboarding rather than past the consent. Clearing the settings store ("Delete all data", Story 5.9) flips it back to
 * Required by itself.
 *
 * It starts from the last gate this activity showed (saved state), so a rotation or a theme change never flashes the
 * empty screen over Home (the stored progress corrects it at once); only a cold start shows [OnboardingGate.Loading].
 */
@Composable
internal fun onboardingGate(): OnboardingGate {
    var last by rememberSaveable { mutableStateOf(OnboardingGate.Loading) }
    val repository = koinInject<OnboardingRepository>()
    val logger = koinInject<Logger>()
    val gate by remember(repository) {
        repository
            .observe()
            .map { if (it.completed) OnboardingGate.Done else OnboardingGate.Required }
            .catch { cause ->
                // Any other failure (a bug) still never lets the app past the consent.
                logger.log(LogEvent.OperationFailed(READ_GATE, cause.message ?: cause::class.simpleName.orEmpty()))
                emit(OnboardingGate.Required)
            }.distinctUntilChanged()
    }.collectAsState(initial = last)
    SideEffect { if (gate != OnboardingGate.Loading) last = gate }
    return gate
}

/** The operation logged when the gate cannot follow the stored progress. */
internal const val READ_GATE = "read onboarding gate"

/**
 * The stack the app shows over [backStack] (Stories 2.6 and 5.10): only the session lock while [locked] (it wins, so a
 * ring or a snooze is never held up), else only onboarding until it is completed, else [backStack] itself; null while
 * the stored onboarding progress is not read yet (the neutral screen shows). A `SideEffect` then makes [backStack] match
 * ([applySessionLock], [applyOnboarding]); onboarding returns after a session that started during it.
 */
@Composable
internal fun shownStack(
    backStack: MutableList<NavKey>,
    locked: Boolean,
): List<NavKey>? {
    val gate = onboardingGate()
    if (!locked && gate == OnboardingGate.Loading) return null
    val onboarding = !locked && gate == OnboardingGate.Required
    SideEffect {
        backStack.applySessionLock(locked)
        if (!locked) backStack.applyOnboarding(onboarding)
    }
    return when {
        locked -> LockedStack
        onboarding -> OnboardingStack
        else -> backStack
    }
}

/** What a locked app shows: only the session lock. */
private val LockedStack: List<NavKey> = listOf(Route.SessionInProgress)

/** What the app shows until onboarding is completed: only onboarding. */
private val OnboardingStack: List<NavKey> = listOf(Route.Onboarding)

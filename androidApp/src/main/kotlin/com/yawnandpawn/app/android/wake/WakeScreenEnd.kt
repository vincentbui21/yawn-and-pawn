package com.yawnandpawn.app.android.wake

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionState

/**
 * How the wake screen [activity] ends (Story 3.3): it follows what rings, and once nothing rings any more it shows the
 * Success screen when the session it showed completed, or else closes. Before any ring it waits. [ended] is the engine's
 * last ended session (`SessionEngine.ended`): the engine's state may skip from Completed to Idle, so how a session
 * ended is read there.
 *
 * Success is UI-only state keyed by `sessionId`, kept across a recreation (saved state). Leaving it (Home, or the
 * screen going off) closes the screen, so the next app open shows Home. Create it in `onCreate`, after `super.onCreate`.
 */
internal class WakeScreenEnd(
    private val activity: ComponentActivity,
    private val ended: () -> SessionState.Active?,
) : DefaultLifecycleObserver {
    /** The screen has shown a ring (a session or the emergency ring); until then it never closes by itself. */
    private var seen = false

    /** The last session this screen showed ringing, quiet, loud or snoozed. */
    private var shownSessionId: String? = null

    /** The session whose Success screen is shown; null while anything else shows. */
    var successSessionId by mutableStateOf<String?>(null)
        private set

    init {
        activity.savedStateRegistry.consumeRestoredStateForKey(KEY)?.let {
            seen = it.getBoolean(KEY_SEEN)
            shownSessionId = it.getString(KEY_SHOWN)
            successSessionId = it.getString(KEY_SUCCESS)
        }
        activity.savedStateRegistry.registerSavedStateProvider(KEY) {
            Bundle().apply {
                putBoolean(KEY_SEEN, seen)
                putString(KEY_SHOWN, shownSessionId)
                putString(KEY_SUCCESS, successSessionId)
            }
        }
        activity.lifecycle.addObserver(this)
    }

    /**
     * Called with what rings now: [active] (a session ringing, quiet, loud or snoozed, or the emergency ring) and that
     * session's id. A ring is remembered and replaces any Success.
     */
    fun follow(
        active: Boolean,
        ringingSessionId: String?,
    ) {
        when {
            active -> {
                seen = true
                if (ringingSessionId != null) shownSessionId = ringingSessionId
                successSessionId = null
            }

            !seen || successSessionId != null -> {
                Unit
            }

            // Read after the state: the engine sets `ended` first, so a session seen gone there has its ending here.
            shownSessionId?.let(::completed) != null -> {
                successSessionId = shownSessionId
            }

            else -> {
                activity.finish()
            }
        }
    }

    /** The completed session whose Success shows; null when none shows or this process no longer knows it. */
    fun success(): SessionData? = successSessionId?.let(::completed)

    override fun onStop(owner: LifecycleOwner) {
        if (successSessionId != null && !activity.isChangingConfigurations) activity.finish()
    }

    /** The session [sessionId] when it is the last one that ended here and it completed (not Missed). */
    private fun completed(sessionId: String): SessionData? =
        (ended() as? SessionState.Completed)?.session?.takeIf { it.sessionId == sessionId }

    private companion object {
        const val KEY = "com.yawnandpawn.app.wake.end"
        const val KEY_SEEN = "seen"
        const val KEY_SHOWN = "shownSessionId"
        const val KEY_SUCCESS = "successSessionId"
    }
}

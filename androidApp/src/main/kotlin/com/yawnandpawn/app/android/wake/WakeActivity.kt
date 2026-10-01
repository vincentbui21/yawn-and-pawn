package com.yawnandpawn.app.android.wake

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.format.is24HourClock
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.wake_im_up
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.wake.WakePrimaryButton
import com.yawnandpawn.app.ui.wake.WakeSurface
import com.yawnandpawn.app.ui.wake.wakeContentPadding
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.android.ext.android.inject

/**
 * The wake screen over the lock screen (AD-5). A skeleton: Story 1.15 replaces its content with the full Ringing screen.
 *
 * It opens only from the ringing notification (its full-screen intent or a tap on it), never from the background. It
 * shows over the lock screen and turns the screen on (`setShowWhenLocked` / `setTurnScreenOn` on API 27+, window flags
 * on API 26), keeps the screen on, and Back does nothing. It renders from the engine's in-memory state with no loading
 * state, under `PpsTheme(wake = true)`: the alarm time and "I'm up" (72 dp). "I'm up" dispatches `ImUpTapped` (in the
 * emergency ring it stops the ring); any other tap dispatches `UserInteracted`. Dispatches are launched on
 * [ApplicationScope], outside composition. Opened before the session starts, it waits for it; it finishes once a session
 * (or emergency ring) it showed is over: Idle, Completed or Missed and no emergency ring.
 */
class WakeActivity : ComponentActivity() {
    private val engine: SessionEngine by inject()
    private val runtime: WakeRuntime by inject()
    private val appScope: ApplicationScope by inject()
    private val timeZones: TimeZoneProvider by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                // The wake screen is left only by ending the session (EXPERIENCE.md: Back does nothing).
                override fun handleOnBackPressed() = Unit
            },
        )
        setContent {
            val state by engine.state.collectAsState()
            val emergency by runtime.emergency.collectAsState()
            // The full-screen intent can open the screen just before the session starts (the service posts the ringing
            // notification first): it waits for a session or an emergency ring, and closes only once that is over.
            val active = state.isRinging() || emergency != null
            var seen by remember { mutableStateOf(false) }
            LaunchedEffect(active) {
                if (active) {
                    seen = true
                } else if (seen) {
                    finish()
                }
            }
            val alarmAt = emergency?.alarmAt ?: (state as? SessionState.Active)?.session?.config?.scheduledAt
            WakeSkeleton(
                time = alarmAt?.toLocalDateTime(timeZones.current())?.time,
                onImUp = { if (emergency != null) runtime.stopEmergency() else send(SessionEvent.ImUpTapped) },
                onInteracted = { send(SessionEvent.UserInteracted) },
            )
        }
    }

    private fun send(event: SessionEvent) {
        appScope.launch { engine.dispatch(event) }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        /** The test tag of the "I'm up" button. */
        const val IM_UP_TAG = "wake_im_up"

        /** The intent of the ringing notification. */
        fun intent(context: Context): Intent =
            Intent(context, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

        /** A session the wake screen is for: ringing, quiet, loud or snoozed. */
        private fun SessionState.isRinging(): Boolean = this is SessionState.Ring || this is SessionState.Snoozed
    }
}

/** The Story 1.14 wake screen: the alarm [time] and "I'm up". Any tap outside the button is [onInteracted]. */
@Composable
private fun WakeSkeleton(
    time: LocalTime?,
    onImUp: () -> Unit,
    onInteracted: () -> Unit,
) {
    WakeSurface(modifier = Modifier.pointerInput(Unit) { detectTapGestures { onInteracted() } }) {
        val is24Hour = is24HourClock()
        Column(
            modifier = Modifier.fillMaxSize().wakeContentPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                time?.let {
                    Text(
                        text = formatClockTime(it, is24Hour),
                        style = PpsTheme.typography.clockXl,
                        color = PpsTheme.colors.text,
                        maxLines = 1,
                    )
                }
            }
            WakePrimaryButton(
                text = stringResource(Res.string.wake_im_up),
                onClick = onImUp,
                modifier = Modifier.testTag(WakeActivity.IM_UP_TAG),
                pulse = true,
            )
        }
    }
}

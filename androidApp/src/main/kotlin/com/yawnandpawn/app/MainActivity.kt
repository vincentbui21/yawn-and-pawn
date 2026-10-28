package com.yawnandpawn.app

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.qr.AndroidCameraPermission
import com.yawnandpawn.app.android.recording.AndroidMicrophonePermission
import com.yawnandpawn.app.android.reliability.AndroidNotificationPermission
import com.yawnandpawn.app.android.screen.forwardToWakeScreenWhileResumed
import com.yawnandpawn.app.android.wake.WakeRuntime
import com.yawnandpawn.app.core.billing.PurchaseCoordinator
import com.yawnandpawn.app.core.billing.ReplayGrantLedger
import com.yawnandpawn.app.core.config.GlobalSettingsRepository
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.ThemeMode
import com.yawnandpawn.app.ui.App
import com.yawnandpawn.app.ui.nav.AppOpenRequests
import com.yawnandpawn.app.ui.nav.SkipRequests
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.colors
import com.yawnandpawn.app.ui.theme.resolveColorSet
import com.yawnandpawn.app.ui.theme.toPps
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * The single activity of the main app; hosts the Compose UI from :composeApp. It draws edge-to-edge (transparent
 * system bars over the theme `bg`, icons light or dark with the system setting), and the manifest sets
 * `adjustResize`, so the IME insets reach Compose and the editor's Save stays above the keyboard.
 *
 * While it is started it lends the notification permission (Story 1.19) its launcher for the system dialog, so the
 * editor can ask after the first save; the answer needs no handling (the Home banner reflects it). It lends the camera
 * permission its launcher the same way (Story 3.10), and hands the answer back to the screen that asked.
 *
 * While it is resumed and the alarm rings (Ringing, Grace, Loud or an emergency ring), it opens the wake screen
 * (Story 2.5): opening the app during a ring brings the user back to the alarm.
 *
 * Launched (or brought back) with [EXTRA_OPEN_TAB] = [OPEN_PROGRESS] (the weekly summary notification, Story 6.8), it
 * asks the navigation to open Progress ([AppOpenRequests]); the extra is then removed, so a recreation never opens it
 * again. During a session or onboarding the request is dropped.
 *
 * Launched (or brought back) with [EXTRA_SKIP_ALARM] (a pre-alarm notification's "Skip this one", Story 7.11), it hands
 * the alarm id to [SkipRequests], and the app asks "Skip your {time} alarm? This is logged." over Home; nothing is skipped
 * before "Skip". The extra is removed the same way, and dropped during a session or onboarding.
 */
class MainActivity : ComponentActivity() {
    private val notificationPermission: AndroidNotificationPermission by inject()
    private val engine: SessionEngine by inject()
    private val runtime: WakeRuntime by inject()
    private val appScope: ApplicationScope by inject()

    // Story 4.10: a granted payment left unsettled (a consume that failed, a crash) is settled again on every start.
    private val replayLedger: ReplayGrantLedger by inject()

    private val purchases: PurchaseCoordinator by inject()

    private val cameraPermission: AndroidCameraPermission by inject()

    private val microphonePermission: AndroidMicrophonePermission by inject()

    private val settings: GlobalSettingsRepository by inject()

    private val openRequests: AppOpenRequests by inject()

    private val skipRequests: SkipRequests by inject()

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Story 3.10: the camera dialog (picking QR/Barcode, QR registration); "Don't ask again" answers false at once.
    // Story 5.4: a "no" after which the dialog cannot show again is blocked (the checklist then opens the app details).
    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraPermission.onResult(granted, blocked = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA))
        }

    // Story 7.2: the microphone dialog, asked only by the first record tap on the Recordings screen.
    private val requestMicrophone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphonePermission.onResult(
                granted,
                blocked = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        paintWindowInStoredTheme()
        // A restore entry point (Story 2.1): a session the last process left in runtime.db is taken over here, in the
        // foreground, where its wake service may start. Off the main thread, outside composition.
        appScope.launch { engine.restore() }
        // This activity's own launcher: a stopping old instance detaches only its own, never a newer one's.
        val launch = { requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        val launchCamera = { requestCamera.launch(Manifest.permission.CAMERA) }
        val launchMicrophone = { requestMicrophone.launch(Manifest.permission.RECORD_AUDIO) }
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    notificationPermission.attach(launch)
                    cameraPermission.attach(launchCamera)
                    microphonePermission.attach(launchMicrophone)
                    appScope.launch { replayLedger() }
                }

                // Story 4.11: a payment whose result was lost (a crash, a kill) is found and reconciled on every resume.
                override fun onResume(owner: LifecycleOwner) = purchases.onAppResumed()

                override fun onStop(owner: LifecycleOwner) {
                    notificationPermission.detach(launch)
                    cameraPermission.detach(launchCamera)
                    microphonePermission.detach(launchMicrophone)
                }
            },
        )
        // During a ring the app hands over to the wake screen (Story 2.5).
        forwardToWakeScreenWhileResumed(this, engine, runtime)
        // Story 6.8: a fresh launch from the weekly summary opens Progress; a recreation (saved state) or a launch from
        // Recents (the old intent again) does not.
        val fromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !fromHistory) {
            openRequestedTab(intent)
        } else {
            intent.removeExtra(EXTRA_OPEN_TAB)
            intent.removeExtra(EXTRA_SKIP_ALARM)
        }
        setContent {
            App()
        }
    }

    /** Story 6.8: the weekly summary tapped while the app is open (single top). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedTab(intent)
    }

    /**
     * Asks the navigation for the tab in [EXTRA_OPEN_TAB], or the skip of the alarm in [EXTRA_SKIP_ALARM] (Story 7.11), if
     * any, and removes the extras so each is handled once.
     */
    private fun openRequestedTab(intent: Intent) {
        if (intent.getStringExtra(EXTRA_OPEN_TAB) == OPEN_PROGRESS) openRequests.open(AppTab.Progress)
        intent.getStringExtra(EXTRA_SKIP_ALARM)?.let(skipRequests::request)
        intent.removeExtra(EXTRA_OPEN_TAB)
        intent.removeExtra(EXTRA_SKIP_ALARM)
    }

    /**
     * Story 5.1 review: the window behind the first Compose frame gets the stored theme's `bg` (the last-known settings,
     * as `App` uses), so Dark on a light phone never flashes a light window, nor Light a dark one. The system splash
     * before it follows the phone (values-night); overriding it would need `UiModeManager.setApplicationNightMode`
     * (API 31+), which recreates the app on every change, so it is not used.
     */
    private fun paintWindowInStoredTheme() {
        val mode = settings.lastKnown()?.settings?.themeMode ?: ThemeMode.System
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val colors = resolveColorSet(mode.toPps(), wake = false, systemInDarkTheme = systemDark).colors()
        window.setBackgroundDrawable(ColorDrawable(colors.bg.toArgb()))
    }

    companion object {
        /** The tab to open (Story 6.8); [OPEN_PROGRESS] is the only value. */
        const val EXTRA_OPEN_TAB = "openTab"

        /** [EXTRA_OPEN_TAB]'s value for Progress (the weekly summary notification). */
        const val OPEN_PROGRESS = "progress"

        /** The alarm id whose "Skip this one" was tapped on its pre-alarm notification (Story 7.11). */
        const val EXTRA_SKIP_ALARM = "skipAlarm"
    }
}

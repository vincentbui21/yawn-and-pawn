package com.yawnandpawn.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.qr.AndroidCameraPermission
import com.yawnandpawn.app.android.reliability.AndroidNotificationPermission
import com.yawnandpawn.app.android.screen.forwardToWakeScreenWhileResumed
import com.yawnandpawn.app.android.wake.WakeRuntime
import com.yawnandpawn.app.core.billing.PurchaseCoordinator
import com.yawnandpawn.app.core.billing.ReplayGrantLedger
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.ui.App
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

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Story 3.10: the camera dialog (picking QR/Barcode, QR registration); "Don't ask again" answers false at once.
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { cameraPermission.onResult(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A restore entry point (Story 2.1): a session the last process left in runtime.db is taken over here, in the
        // foreground, where its wake service may start. Off the main thread, outside composition.
        appScope.launch { engine.restore() }
        // This activity's own launcher: a stopping old instance detaches only its own, never a newer one's.
        val launch = { requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        val launchCamera = { requestCamera.launch(Manifest.permission.CAMERA) }
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    notificationPermission.attach(launch)
                    cameraPermission.attach(launchCamera)
                    appScope.launch { replayLedger() }
                }

                // Story 4.11: a payment whose result was lost (a crash, a kill) is found and reconciled on every resume.
                override fun onResume(owner: LifecycleOwner) = purchases.onAppResumed()

                override fun onStop(owner: LifecycleOwner) {
                    notificationPermission.detach(launch)
                    cameraPermission.detach(launchCamera)
                }
            },
        )
        // During a ring the app hands over to the wake screen (Story 2.5).
        forwardToWakeScreenWhileResumed(this, engine, runtime)
        setContent {
            App()
        }
    }
}

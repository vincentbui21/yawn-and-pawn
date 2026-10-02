package com.yawnandpawn.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.yawnandpawn.app.android.reliability.AndroidNotificationPermission
import com.yawnandpawn.app.ui.App
import org.koin.android.ext.android.inject

/**
 * The single activity of the main app; hosts the Compose UI from :composeApp. It draws edge-to-edge (transparent
 * system bars over the theme `bg`, icons light or dark with the system setting), and the manifest sets
 * `adjustResize`, so the IME insets reach Compose and the editor's Save stays above the keyboard.
 *
 * While it is started it lends the notification permission (Story 1.19) its launcher for the system dialog, so the
 * editor can ask after the first save; the answer needs no handling (the Home banner reflects it).
 */
class MainActivity : ComponentActivity() {
    private val notificationPermission: AndroidNotificationPermission by inject()

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // This activity's own launcher: a stopping old instance detaches only its own, never a newer one's.
        val launch = { requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = notificationPermission.attach(launch)

                override fun onStop(owner: LifecycleOwner) = notificationPermission.detach(launch)
            },
        )
        setContent {
            App()
        }
    }
}

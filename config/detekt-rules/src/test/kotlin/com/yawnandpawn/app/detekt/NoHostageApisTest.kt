package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoHostageApisTest {
    private val rule = NoHostageApis(Config.empty)

    private fun findings(
        code: String,
        packageName: String = APP,
    ) = rule.lint("package $packageName\n\n$code\n")

    private fun assertOne(
        code: String,
        packageName: String = APP,
    ) {
        val found = findings(code, packageName)
        assertEquals(1, found.size, "$code\n${found.map { it.message }}")
        assertTrue(found.single().message.contains("NFR-13"), code)
    }

    private fun assertNone(
        code: String,
        packageName: String = APP,
    ) = assertEquals(emptyList(), findings(code, packageName).map { it.message }, code)

    @Test
    fun `lock task, task moves, overlays, the keyguard, device admin and accessibility are reported`() {
        listOf(
            "fun f(activity: android.app.Activity) = activity.startLockTask()",
            "fun f(admin: Any, c: android.content.ComponentName) = (admin as Policy).setLockTaskPackages(c, arrayOf(\"x\"))",
            "fun f(am: android.app.ActivityManager) = am.moveTaskToFront(1, 0)",
            "fun f(km: android.app.KeyguardManager) = km.newKeyguardLock(\"alarm\")",
            "fun f(wm: android.view.WindowManager, v: android.view.View, p: Any) = wm.addView(v, p)",
            "fun f(c: android.content.Context, v: android.view.View, p: Any) = " +
                "c.getSystemService(android.view.WindowManager::class.java).addView(v, p)",
            "val type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY",
            "val type = android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT",
            "lateinit var admin: DevicePolicyManager",
            "class Watcher : AccessibilityService()",
        ).forEach(::assertOne)
    }

    @Test
    fun `addView on a window manager is reported whatever the receiver looks like`() {
        listOf(
            "fun f(activity: android.app.Activity, v: android.view.View, p: Any) = activity.windowManager.addView(v, p)",
            "class S : android.app.Activity() { fun f(v: android.view.View, p: Any) = windowManager.addView(v, p) }",
            "fun f(wm: android.view.WindowManager?, v: android.view.View, p: Any) = wm?.addView(v, p)",
            "fun f(wm: android.view.WindowManager, v: android.view.View, p: Any) = with(wm) { addView(v, p) }",
            "fun f(wm: android.view.WindowManager, v: android.view.View, p: Any) = wm.apply { addView(v, p) }",
            "fun f(wm: android.view.WindowManager, v: android.view.View, p: Any) = wm.let { it.addView(v, p) }",
            "fun f(c: android.content.Context, v: android.view.View, p: Any) {\n" +
                "    val overlay = c.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager\n" +
                "    overlay.addView(v, p)\n}",
            "class S(val c: android.content.Context) {\n" +
                "    private val overlay by lazy { c.getSystemService(android.content.Context.WINDOW_SERVICE) }\n" +
                "    fun f(v: android.view.View, p: Any) = overlay.addView(v, p)\n}",
        ).forEach(::assertOne)
    }

    @Test
    fun `compliant neighbours of those calls pass`() {
        listOf(
            "fun f(group: android.view.ViewGroup, v: android.view.View) = group.addView(v)",
            "fun f(group: android.view.ViewGroup, v: android.view.View) = with(group) { addView(v) }",
            "class G(c: android.content.Context) : android.widget.FrameLayout(c) { fun f(v: android.view.View) = addView(v) }",
            "fun f(activity: android.app.Activity) = activity.moveTaskToBack(true)",
            "val flags = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON",
            "fun f(km: android.app.KeyguardManager) = km.isKeyguardLocked",
        ).forEach(::assertNone)
    }

    @Test
    fun `telephony is reported, the audio mode is the compliant way to see a call (Story 2-7)`() {
        listOf(
            "import android.telephony.TelephonyManager\nlateinit var phone: TelephonyManager",
            "fun f(c: android.content.Context) = c.getSystemService(TelephonyManager::class.java)",
            "class Calls : PhoneStateListener()",
            "class Calls : TelephonyCallback()",
            "fun f(t: Any, e: java.util.concurrent.Executor, cb: Any) = (t as Phone).registerTelephonyCallback(e, cb)",
        ).forEach(::assertOne)
        assertNone("fun inCall(audio: android.media.AudioManager) = audio.mode == android.media.AudioManager.MODE_IN_CALL")
    }

    @Test
    fun `an import alias of a banned name is reported`() {
        listOf(
            "import android.telephony.TelephonyManager as Tm\nlateinit var phone: Tm",
            "import android.app.admin.DevicePolicyManager as Dpm\nlateinit var admin: Dpm",
            "import android.media.session.MediaSession as Session\nfun f(c: android.content.Context) = Session(c, \"alarm\")",
            "import androidx.media.VolumeProviderCompat as Keys\nlateinit var provider: Keys",
            "import android.view.KeyEvent.KEYCODE_HOME as H\nfun home(code: Int) = code == H",
            "import androidx.compose.ui.input.key.Key.Companion.Power as P\nval power = P",
        ).forEach(::assertOne)
        assertNone("import android.media.AudioManager as Audio\nfun f(audio: Audio) = audio.isMusicActive")
    }

    @Test
    fun `media sessions, volume providers and audio rerouting are reported (Story 2-8)`() {
        listOf(
            "fun f(c: android.content.Context) = MediaSession(c, \"alarm\")",
            "fun f(c: android.content.Context) = MediaSessionCompat(c, \"alarm\")",
            "class Keys : VolumeProvider(0, 15, 7)",
            "lateinit var provider: VolumeProviderCompat",
            "import android.media.session.MediaSession\nval create = ::MediaSession",
            "import android.media.session.MediaSession\nval type = MediaSession::class",
            "val type = android.media.VolumeProvider::class.java",
            "fun f(audio: android.media.AudioManager, d: android.media.AudioDeviceInfo) = audio.setCommunicationDevice(d)",
            "fun f(audio: android.media.AudioManager) = audio.setSpeakerphoneOn(true)",
            "fun f(audio: android.media.AudioManager) { audio.isSpeakerphoneOn = true }",
            "fun f(audio: android.media.AudioManager) = audio.isSpeakerphoneOn",
            "fun f(audio: android.media.AudioManager) = audio.isSpeakerphoneOn()",
            "fun f(player: android.media.MediaPlayer, d: android.media.AudioDeviceInfo) = player.setPreferredDevice(d)",
            "fun f(audio: android.media.AudioManager, c: android.content.ComponentName) = audio.registerMediaButtonEventReceiver(c)",
            "fun f(audio: android.media.AudioManager) = audio::setSpeakerphoneOn",
            "fun f(audio: android.media.AudioManager) = audio.startBluetoothSco()",
            "fun f(audio: android.media.AudioManager) = audio.setBluetoothScoOn(true)",
            "fun f(audio: android.media.AudioManager) { audio.isBluetoothScoOn = true }",
            "fun f(audio: android.media.AudioManager) = audio.isBluetoothScoOn",
            "fun f(audio: android.media.AudioManager) = audio.setMode(android.media.AudioManager.MODE_IN_COMMUNICATION)",
            "fun f(audio: android.media.AudioManager) = audio.setMode(android.media.AudioManager.MODE_IN_CALL)",
            "fun f(audio: android.media.AudioManager) { audio.mode = android.media.AudioManager.MODE_IN_COMMUNICATION }",
        ).forEach(::assertOne)
        listOf(
            "fun f(audio: android.media.AudioManager, i: Int) = audio.setStreamVolume(android.media.AudioManager.STREAM_ALARM, i, 0)",
            "fun f(audio: android.media.AudioManager) = audio.setMode(android.media.AudioManager.MODE_NORMAL)",
        ).forEach(::assertNone)
    }

    @Test
    fun `an activity start from a service, a receiver or the receivers' and wake packages is reported`() {
        assertOne(
            """
            class WakeService : android.app.Service() {
                override fun onBind(intent: android.content.Intent?) = null
                fun open(i: android.content.Intent) = startActivity(i)
            }
            """.trimIndent(),
        )
        assertOne(
            """
            class Fired : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                    c.startActivities(arrayOf(i))
                }
            }
            """.trimIndent(),
        )
        assertOne(
            "fun open(c: android.content.Context, i: android.content.Intent) = c.startActivity(i)",
            "com.yawnandpawn.app.android.wake",
        )
        assertOne(
            "class Runtime { fun open(c: android.content.Context, i: android.content.Intent) = c.startActivity(i) }",
            RECEIVERS,
        )
    }

    @Test
    fun `every activity start form from every background component is reported`() {
        val starts =
            listOf(
                "c.startActivity(i)",
                "c.startActivities(arrayOf(i))",
                "(c as android.app.Activity).startActivityIfNeeded(i, 0)",
                "c.startIntentSender(null, null, 0, 0, 0)",
                "pendingIntent.send()",
                "listOf(i).forEach(c::startActivity)",
            )
        val components =
            listOf(
                "android.app.Service()",
                "android.app.job.JobService()",
                "android.content.BroadcastReceiver()",
                "androidx.work.Worker(c, p)",
                "androidx.work.CoroutineWorker(c, p)",
                "android.content.ContentProvider()",
                "android.app.Application()",
            )
        components.forEach { component ->
            starts.forEach { start ->
                assertOne(
                    """
                    abstract class Background(
                        val c: android.content.Context,
                        p: Any,
                        val pendingIntent: android.app.PendingIntent,
                    ) : $component {
                        fun open(i: android.content.Intent) { $start }
                    }
                    """.trimIndent(),
                    UI,
                )
            }
        }
    }

    @Test
    fun `an activity start in an object literal or a local class inside a background component is reported`() {
        assertOne(
            """
            class Fired : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                    val later = object : Runnable { override fun run() = c.startActivity(i) }
                    later.run()
                }
            }
            """.trimIndent(),
            UI,
        )
        assertOne(
            """
            class Sync : android.app.Service() {
                override fun onBind(intent: android.content.Intent?) = null
                fun f(i: android.content.Intent) {
                    class Opener { fun open() = startActivity(i) }
                    Opener().open()
                }
            }
            """.trimIndent(),
            UI,
        )
        assertOne(
            """
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) = c.startActivity(i)
            }
            """.trimIndent(),
            UI,
        )
    }

    @Test
    fun `an activity start from WakeActivity, the screen in front or an ordinary activity passes`() {
        assertNone(
            """
            class WakeActivity : android.app.Activity() {
                fun again(i: android.content.Intent) = startActivity(i)
                companion object { fun open(c: android.content.Context, i: android.content.Intent) = c.startActivity(i) }
            }
            """.trimIndent(),
            "com.yawnandpawn.app.android.wake",
        )
        assertNone(
            "class Opener(val c: android.content.Context) { fun open(i: android.content.Intent) = c.startActivity(i) }",
            "com.yawnandpawn.app.android.screen",
        )
        assertNone(
            "class Settings(val c: android.content.Context) { fun open(i: android.content.Intent) = c.startActivity(i) }",
            "com.yawnandpawn.app.android.reliability",
        )
        assertNone("class MainActivity : android.app.Activity() { fun settings(i: android.content.Intent) = startActivity(i) }")
        assertNone(
            "class Events(val effects: kotlinx.coroutines.channels.Channel<Int>) : android.app.Service() {\n" +
                "    suspend fun f() = effects.send(1)\n}",
            UI,
        )
    }

    @Test
    fun `Home, Recents and Power keys are reported anywhere but an import, volume keys pass`() {
        listOf("KEYCODE_HOME", "KEYCODE_APP_SWITCH", "KEYCODE_POWER").forEach { key ->
            assertOne(
                """
                class Screen : android.app.Activity() {
                    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
                        keyCode == android.view.KeyEvent.$key || super.onKeyDown(keyCode, event)
                }
                """.trimIndent(),
            )
            assertOne("fun isSystem(code: Int) = code == android.view.KeyEvent.$key")
            assertOne("import android.view.KeyEvent.$key\nval system = setOf($key)")
        }
        assertOne(
            """
            class Screen : android.app.Activity() {
                override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
                    event.keyCode == android.view.KeyEvent.KEYCODE_HOME || super.dispatchKeyEvent(event)
            }
            """.trimIndent(),
        )
        listOf("Home", "AppSwitch", "Power").forEach { key ->
            assertOne(
                "import androidx.compose.ui.input.key.Key\n" +
                    "fun swallow(e: androidx.compose.ui.input.key.KeyEvent) = e.key == Key.$key",
            )
            assertOne("val k = androidx.compose.ui.input.key.Key.$key")
        }
        listOf(
            """
            class Screen : android.app.Activity() {
                override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
                    keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || super.onKeyDown(keyCode, event)
            }
            """.trimIndent(),
            "import androidx.compose.ui.input.key.Key\nval k = Key.VolumeUp",
            "enum class Tab { Home, Power }\nval t = Tab.Home",
        ).forEach(::assertNone)
    }

    private companion object {
        const val APP = "com.yawnandpawn.app"
        const val RECEIVERS = "com.yawnandpawn.app.android"
        const val UI = "com.yawnandpawn.app.ui"
    }
}

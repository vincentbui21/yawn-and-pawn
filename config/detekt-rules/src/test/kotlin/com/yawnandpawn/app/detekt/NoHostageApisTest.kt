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
        packageName: String = "com.yawnandpawn.app.android",
    ) = rule.lint("package $packageName\n\n$code\n")

    private fun assertOne(
        code: String,
        packageName: String = "com.yawnandpawn.app.android",
    ) {
        val found = findings(code, packageName)
        assertEquals(1, found.size, "$code\n${found.map { it.message }}")
        assertTrue(found.single().message.contains("NFR-13"), code)
    }

    private fun assertNone(
        code: String,
        packageName: String = "com.yawnandpawn.app.android",
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
    fun `compliant neighbours of those calls pass`() {
        listOf(
            "fun f(group: android.view.ViewGroup, v: android.view.View) = group.addView(v)",
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
    fun `media sessions, volume providers and audio rerouting are reported (Story 2-8)`() {
        listOf(
            "fun f(c: android.content.Context) = MediaSession(c, \"alarm\")",
            "fun f(c: android.content.Context) = MediaSessionCompat(c, \"alarm\")",
            "class Keys : VolumeProvider(0, 15, 7)",
            "lateinit var provider: VolumeProviderCompat",
            "fun f(audio: android.media.AudioManager, d: android.media.AudioDeviceInfo) = audio.setCommunicationDevice(d)",
            "fun f(audio: android.media.AudioManager) = audio.setSpeakerphoneOn(true)",
            "fun f(audio: android.media.AudioManager) { audio.isSpeakerphoneOn = true }",
            "fun f(player: android.media.MediaPlayer, d: android.media.AudioDeviceInfo) = player.setPreferredDevice(d)",
            "fun f(audio: android.media.AudioManager, c: android.content.ComponentName) = audio.registerMediaButtonEventReceiver(c)",
            "fun f(audio: android.media.AudioManager) = audio::setSpeakerphoneOn",
        ).forEach(::assertOne)
        assertNone(
            "fun f(audio: android.media.AudioManager, i: Int) = audio.setStreamVolume(android.media.AudioManager.STREAM_ALARM, i, 0)",
        )
    }

    @Test
    fun `an activity start from a service, a receiver or the wake and receiver packages is reported`() {
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
            "com.yawnandpawn.app.android.receiver",
        )
    }

    @Test
    fun `an activity start from WakeActivity or the screen in front passes`() {
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
        assertNone("class MainActivity : android.app.Activity() { fun settings(i: android.content.Intent) = startActivity(i) }")
    }

    @Test
    fun `overriding a key handler for Home, Recents or Power is reported, volume keys pass`() {
        listOf("KEYCODE_HOME", "KEYCODE_APP_SWITCH", "KEYCODE_POWER").forEach { key ->
            assertOne(
                """
                class Screen : android.app.Activity() {
                    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
                        keyCode == android.view.KeyEvent.$key || super.onKeyDown(keyCode, event)
                }
                """.trimIndent(),
            )
        }
        assertOne(
            """
            class Screen : android.app.Activity() {
                override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
                    event.keyCode == android.view.KeyEvent.KEYCODE_HOME || super.dispatchKeyEvent(event)
            }
            """.trimIndent(),
        )
        assertNone(
            """
            class Screen : android.app.Activity() {
                override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
                    keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || super.onKeyDown(keyCode, event)
            }
            """.trimIndent(),
        )
    }
}

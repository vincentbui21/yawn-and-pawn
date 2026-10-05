package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoAudioCaptureOrRoutingTest {
    private val rule = NoAudioCaptureOrRouting(Config.empty)

    private fun findings(code: String) = rule.lint("package com.yawnandpawn.app.android.wake\n\n$code\n")

    @Test
    fun `a media session or a volume provider is reported`() {
        val violating =
            listOf(
                "import android.media.session.MediaSession\nfun f(context: android.content.Context) = MediaSession(context, \"alarm\")",
                "import android.support.v4.media.session.MediaSessionCompat\nfun f(context: android.content.Context) = " +
                    "MediaSessionCompat(context, \"alarm\")",
                "import android.media.VolumeProvider\nclass Keys : VolumeProvider(0, 15, 7)",
                "import androidx.media.VolumeProviderCompat\nlateinit var provider: VolumeProviderCompat",
            )

        violating.forEach { code ->
            val found = findings(code)
            assertTrue(found.isNotEmpty(), code)
            assertTrue(found.first().message.contains("VolumeKeyGate"), code)
        }
    }

    @Test
    fun `rerouting audio or registering a media button receiver is reported`() {
        val violating =
            listOf(
                "fun f(audio: android.media.AudioManager, device: android.media.AudioDeviceInfo) = audio.setCommunicationDevice(device)",
                "fun f(audio: android.media.AudioManager) = audio.setSpeakerphoneOn(true)",
                "fun f(audio: android.media.AudioManager) { audio.isSpeakerphoneOn = true }",
                "fun f(player: android.media.MediaPlayer, device: android.media.AudioDeviceInfo) = player.setPreferredDevice(device)",
                "fun f(audio: android.media.AudioManager, c: android.content.ComponentName) = audio.registerMediaButtonEventReceiver(c)",
                "fun f(audio: android.media.AudioManager) = audio::setSpeakerphoneOn",
            )

        violating.forEach { code -> assertEquals(1, findings(code).size, code) }
    }

    @Test
    fun `the wake screen's own key handling and alarm playback are compliant`() {
        val compliant =
            """
            import android.media.AudioAttributes
            import android.media.AudioManager
            import android.view.KeyEvent

            fun consumes(event: KeyEvent): Boolean = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

            fun setForRing(audio: AudioManager, index: Int) = audio.setStreamVolume(AudioManager.STREAM_ALARM, index, 0)

            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            """.trimIndent()

        assertEquals(emptyList(), findings(compliant).map { it.message })
    }
}

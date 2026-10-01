package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.sound.SoundCatalog
import com.yawnandpawn.app.core.sound.SoundLibrary
import com.yawnandpawn.app.core.sound.SoundPreview
import com.yawnandpawn.app.core.sound.SoundRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [SoundLibrary] with [system] ringtones; a catalog sound is available, a system one while it is in [system] and not
 * in [missing], anything else never.
 */
class FakeSoundLibrary(
    var system: List<SoundRef.System> = listOf(SoundRef.System("content://ringtones/argon", "Argon")),
    val missing: MutableSet<SoundRef> = mutableSetOf(),
) : SoundLibrary {
    override suspend fun systemSounds(): List<SoundRef.System> = system

    override suspend fun isAvailable(ref: SoundRef): Boolean =
        ref !in missing &&
            when (ref) {
                is SoundRef.BuiltIn -> SoundCatalog.find(ref) != null
                is SoundRef.System -> ref in system
            }
}

/** [SoundPreview] that records every call; [finish] ends the playing preview as the real sound ending would. */
class FakeSoundPreview : SoundPreview {
    private val playing = MutableStateFlow<String?>(null)
    override val previewing: StateFlow<String?> = playing.asStateFlow()

    /** Each `play` as (encoded ref, volume), in order. */
    val played = mutableListOf<Pair<String, Int>>()
    val volumes = mutableListOf<Int>()
    var stops = 0
        private set

    override fun play(
        ref: SoundRef,
        volumePercent: Int,
    ) {
        played += ref.encode() to volumePercent
        playing.value = ref.encode()
    }

    override fun setVolume(volumePercent: Int) {
        if (playing.value != null) volumes += volumePercent
    }

    override fun stop() {
        stops++
        playing.value = null
    }

    fun finish() {
        playing.value = null
    }
}

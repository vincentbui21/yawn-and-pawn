package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.sound.CustomSound
import com.yawnandpawn.app.core.sound.CustomSoundRepository
import com.yawnandpawn.app.core.sound.ImportResult
import com.yawnandpawn.app.core.sound.SoundCatalog
import com.yawnandpawn.app.core.sound.SoundImporter
import com.yawnandpawn.app.core.sound.SoundLibrary
import com.yawnandpawn.app.core.sound.SoundPreview
import com.yawnandpawn.app.core.sound.SoundRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlin.time.Instant

/**
 * [SoundLibrary] with [system] ringtones; a catalog sound is available, a system one while it is in [system], a user's
 * file (Story 7.4) while its id is in [files], each only when not in [missing]; anything else never.
 */
class FakeSoundLibrary(
    var system: List<SoundRef.System> = listOf(SoundRef.System("content://ringtones/argon", "Argon")),
    val missing: MutableSet<SoundRef> = mutableSetOf(),
    val files: MutableSet<String> = mutableSetOf(),
) : SoundLibrary {
    override suspend fun systemSounds(): List<SoundRef.System> = system

    override suspend fun isAvailable(ref: SoundRef): Boolean =
        ref !in missing &&
            when (ref) {
                is SoundRef.BuiltIn -> SoundCatalog.find(ref) != null
                is SoundRef.System -> ref in system
                is SoundRef.File -> ref.id in files
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

/** An imported sound for tests: "Morning song", 30 s, its copy `{id}.mp3`. */
fun aCustomSound(
    id: String = "sound-1",
    displayName: String = "Morning song",
    durationMs: Long = 30_000,
    createdAt: Instant = DEFAULT_FAKE_INSTANT,
    missing: Boolean = false,
): CustomSound = CustomSound(id, displayName, CustomSound.fileNameFor(id, "mp3"), durationMs, createdAt, missing)

/**
 * In-memory [CustomSoundRepository] (Story 7.4). [files] holds the copies that exist: an added sound adds its file, and
 * [markMissing] removes one as a restore would, so [CustomSound.missing] follows them. Set [failure] to make every call
 * fail.
 */
class FakeCustomSoundRepository(
    initial: List<CustomSound> = emptyList(),
) : CustomSoundRepository {
    private val rows = MutableStateFlow(initial.map { it.copy(missing = false) })
    val files = MutableStateFlow(initial.filterNot { it.missing }.map { it.fileName }.toSet())
    var failure: DomainError? = null

    /** The stored rows as they are, without [CustomSound.missing]. */
    val stored: List<CustomSound>
        get() = rows.value

    override fun observeAll(): Flow<List<CustomSound>> = combine(rows, files) { all, present -> all.map { it.withFile(present) } }

    override suspend fun get(id: String): Outcome<CustomSound?, DomainError> =
        guarded { rows.value.firstOrNull { it.id == id }?.withFile(files.value) }

    override suspend fun add(sound: CustomSound): Outcome<Unit, DomainError> =
        guarded {
            val row = sound.copy(missing = false)
            rows.value = rows.value.filterNot { it.id == row.id } + row
            files.value += row.fileName
        }

    /** The copy of [id] is gone (a restored phone, or storage locked before the first unlock). */
    fun markMissing(id: String) {
        rows.value.firstOrNull { it.id == id }?.let { files.value -= it.fileName }
    }

    private fun CustomSound.withFile(present: Set<String>): CustomSound = copy(missing = fileName !in present)

    private fun <T> guarded(block: () -> T): Outcome<T, DomainError> = failure?.let { Outcome.Failure(it) } ?: Outcome.Success(block())
}

/**
 * Scripted [SoundImporter] (Story 7.4): each [import] records its URI in [imported] and answers [result]; an
 * [ImportResult.Imported] answer is also added to [into], as the real importer stores its row.
 */
class FakeSoundImporter(
    var result: Outcome<ImportResult, DomainError> = Outcome.Success(ImportResult.Imported(aCustomSound())),
    private val into: FakeCustomSoundRepository? = null,
) : SoundImporter {
    val imported = mutableListOf<String>()

    override suspend fun import(uri: String): Outcome<ImportResult, DomainError> {
        imported += uri
        val answer = result
        val sound = ((answer as? Outcome.Success)?.value as? ImportResult.Imported)?.sound
        if (sound != null) into?.add(sound)
        return answer
    }
}

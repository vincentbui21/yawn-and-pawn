package com.yawnandpawn.app.ui.wake

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * One Memory Sequence round on screen (Story 3.8), UI only: the [sequence] plays tile by tile (lit for [LIT], then a
 * [GAP]), and only then may the user tap. A tap the screen accepts lights its tile for [TAP_LIT]. Pure: whoever shows it
 * calls [tick] after [nextTick] (a coroutine on virtual time in tests). With animator duration scale 0 the timing stays
 * the same: these are state changes, not animations.
 *
 * @property frame the playback step: even frames light `sequence[frame / 2]`, odd frames are the gaps; from
 * `2 × sequence.size` on, it is the user's turn.
 */
data class MemoryPlayback(
    val sequence: List<Int>,
    val frame: Int = 0,
    val tapLit: Int? = null,
) {
    /** The sequence is still playing: taps are ignored. */
    val playing: Boolean
        get() = frame < sequence.size * 2

    /** The tile lit now: the played tile, or the one just tapped. */
    val litTile: Int?
        get() = if (playing) sequence.getOrNull(frame / 2)?.takeIf { frame % 2 == 0 } else tapLit

    /** The phase the screen shows. */
    val phase: MemoryPhase
        get() = if (playing) MemoryPhase.Watch else MemoryPhase.YourTurn

    /** How long until the next [tick], or null when nothing changes by itself. */
    val nextTick: Duration?
        get() =
            when {
                playing -> if (frame % 2 == 0) LIT else GAP
                tapLit != null -> TAP_LIT
                else -> null
            }

    /** The next playback step, or the tap light going off. */
    fun tick(): MemoryPlayback = if (playing) copy(frame = frame + 1) else copy(tapLit = null)

    /** [tile] tapped and accepted on the user's turn: it lights briefly. */
    fun tapped(tile: Int): MemoryPlayback = copy(tapLit = tile)

    companion object {
        /** How long a tile of the sequence is lit (EXPERIENCE.md Motion: 350 ms highlight). */
        val LIT: Duration = 350.milliseconds

        /** The gap after each lit tile (150 ms). */
        val GAP: Duration = 150.milliseconds

        /** The brief light of a tapped tile. */
        val TAP_LIT: Duration = 150.milliseconds
    }
}

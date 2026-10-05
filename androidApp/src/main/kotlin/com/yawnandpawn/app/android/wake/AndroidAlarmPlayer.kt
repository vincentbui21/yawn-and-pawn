package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.core.alarm.rampGain
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.log.WakeStage
import com.yawnandpawn.app.core.time.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The only alarm player (AD-5), owned by [WakeRuntime]. It plays one looping sound on the alarm stream
 * ([ALARM_AUDIO_ATTRIBUTES], `USAGE_ALARM`), independent of media and ringer volume.
 *
 * - **Volume:** at the start of a ring it sets `STREAM_ALARM` to the alarm's volume ([AlarmVolume], which saves the
 *   user's own volume); [stop] with `restoreVolume` puts it back at the end of the session.
 * - **Ramp:** with "Gradually increase volume" on, the gain follows the pure `rampGain(elapsed, start, 30 s)`, updated
 *   about every 250 ms; off, the first frame plays at full gain. Elapsed time is monotonic.
 * - **Never silent:** a sound that cannot be opened, or fails during prepare or while ringing, is replaced in the same
 *   ring: a chosen sound by the bundled default, the default by the phone's alarm ringtone (and back), a bounded number
 *   of opens at a time (a sound that played 10 s resets the count). When every open failed, the default is tried again
 *   every 5 s while the ring lasts. [resolver] maps the alarm's sound (a library sound or a phone ringtone, Story 1.17);
 *   an unknown sound reference plays the default. Each fallback is logged without the sound reference or its URI.
 *   Sounds prepare asynchronously (`MediaPlayerPlaybackFactory`); a prepare error, or a sound not prepared within 5 s
 *   (a stalled content provider), falls back like a playback error. A new ring stops the Sound preview ([onRingStart]).
 *
 * Every call is idempotent for the same request and thread-safe (one lock; `MediaPlayer` errors arrive on the main
 * thread). [scope] runs the ramp updates. (Many small functions: one verb per session effect plus the locked helpers
 * behind them; splitting the class would spread its one lock.)
 */
@Suppress("TooManyFunctions")
class AndroidAlarmPlayer(
    private val playbacks: PlaybackFactory,
    private val resolver: SoundResolver,
    private val volume: AlarmVolume,
    private val monotonicClock: MonotonicClock,
    private val scope: CoroutineScope,
    private val logger: Logger,
    /** The ring-start timing: a prepared sound (it starts playing then) is logged as `SoundStarted`. */
    private val timings: WakeTimings = WakeTimings.None,
    /**
     * Runs at the start of every ring, under the player's lock, once [isRinging] is true: the Sound preview stops (Story
     * 1.17). It must not call back into the player.
     */
    private val onRingStart: () -> Unit = {},
) {
    /** What the session asked for; a different request starts the ring over. [rampStart] is null when there is no ramp. */
    private data class Request(
        val soundRef: String,
        val volumePercent: Int,
        val rampStart: Double?,
    )

    /** One open of [sound]: callbacks of an older open (released since) are told apart by identity. */
    private class Opening(
        val sound: AlarmSound,
    ) {
        var prepared = false

        /** When it was prepared (monotonic); one that played [HEALTHY_PLAY] before failing resets the open count. */
        var preparedAt = 0L
    }

    private val lock = Any()
    private var request: Request? = null
    private var playback: Playback? = null
    private var opening: Opening? = null
    private var opens = 0
    private var rampStartedAt = 0L
    private var rampDone = false
    private var rampJob: Job? = null
    private var retryJob: Job? = null
    private var prepareWatchdog: Job? = null

    @Volatile
    private var ringOn = false

    /** The sound that is open (playing, paused or muted), or null when nothing is. */
    @Volatile
    var sound: AlarmSound? = null
        private set

    /** The gain last given to the playback. */
    @Volatile
    var gain: Float = 0f
        private set

    @Volatile
    var isMuted: Boolean = false
        private set

    @Volatile
    var isPaused: Boolean = false
        private set

    /**
     * A ring (or the emergency ring) is on, even between two opens of its sound; the Sound preview keeps out of it. Read
     * without the player's lock, so the preview can ask while holding its own.
     */
    val isRinging: Boolean
        get() = ringOn

    /**
     * Plays [soundRef] at [volumePercent] of the alarm stream, ramping from [rampStartPercent] of it when [gradual], and
     * unmuted and unpaused. The same request again only unmutes and resumes; another one starts the ring over.
     */
    fun play(
        soundRef: String,
        volumePercent: Int,
        gradual: Boolean,
        rampStartPercent: Int,
    ) = synchronized(lock) {
        val wanted = Request(soundRef, volumePercent, if (gradual) rampStartPercent / PERCENT else null)
        isMuted = false
        if (playback != null && wanted == request) {
            if (isPaused) resumeLocked()
            applyGainLocked()
        } else {
            startRingLocked(wanted, resolve(soundRef))
        }
    }

    /** The emergency ring: the bundled default at [volumePercent], full gain at once. */
    fun playDefault(volumePercent: Int) =
        synchronized(lock) {
            if (request?.soundRef == EMERGENCY_REF && playback != null) return@synchronized
            startRingLocked(Request(EMERGENCY_REF, volumePercent, null), AlarmSound.Default)
        }

    /** After a crash in the wake flow (AD-12): the open sound becomes the default, keeping mute, pause and gain. */
    fun switchToDefault() =
        synchronized(lock) {
            if (request == null || (sound == AlarmSound.Default && playback != null)) return@synchronized
            closeLocked()
            opens = 0
            openLocked(AlarmSound.Default)
        }

    /** The grace window: silent, still open. */
    fun mute() =
        synchronized(lock) {
            isMuted = true
            applyGainLocked()
        }

    /** The grace window ended: back to the set volume at full gain (the ramp is over). */
    fun unmute() =
        synchronized(lock) {
            isMuted = false
            rampDone = true
            applyGainLocked()
        }

    /** A call: paused until [resume] (or the next [play]). */
    fun pause() =
        synchronized(lock) {
            isPaused = true
            playback?.pause()
        }

    fun resume() = synchronized(lock) { if (isPaused) resumeLocked() }

    /** Stops and releases the sound; with [restoreVolume] (the session ended) the user's alarm volume comes back. */
    fun stop(restoreVolume: Boolean) =
        synchronized(lock) {
            release()
            if (restoreVolume) volume.restore()
        }

    /**
     * At app start with no session: puts back a user volume a crashed session left saved, unless a ring started
     * meanwhile (checked under the player's lock, so a cold-start ring is never turned down).
     */
    fun restoreVolumeIfSilent() = synchronized(lock) { if (request == null) volume.restore() }

    /** One ramp step: the gain for the time since the ring started. The ramp loop calls it; tests may too. */
    fun updateGain() = synchronized(lock) { applyGainLocked() }

    private fun startRingLocked(
        wanted: Request,
        first: AlarmSound,
    ) {
        release()
        request = wanted
        ringOn = true
        onRingStart()
        volume.setForRing(wanted.volumePercent)
        rampStartedAt = monotonicClock.elapsedMillis()
        rampDone = wanted.rampStart == null
        openLocked(first)
        if (!rampDone) startRampLoop()
    }

    private fun resolve(soundRef: String): AlarmSound =
        resolver.resolve(soundRef) ?: AlarmSound.Default.also {
            logger.log(LogEvent.SoundFellBack("unknown sound reference"))
        }

    /**
     * Opens [first], or the next sound in the fallback chain when it fails, and starts it unless paused. When every try
     * of this round failed, the default is tried again after [RETRY_DELAY], for as long as the ring lasts.
     */
    private fun openLocked(first: AlarmSound) {
        var next: AlarmSound? = first
        while (next != null && opens < MAX_OPENS_PER_RING) {
            opens++
            next = tryOpen(next)
        }
        if (playback == null) {
            logger.log(LogEvent.OperationFailed("play alarm sound", "no sound could be opened; trying the default again"))
            retryJob?.cancel()
            retryJob =
                scope.launch {
                    delay(RETRY_DELAY)
                    synchronized(lock) {
                        if (request != null && playback == null) {
                            opens = 0
                            openLocked(AlarmSound.Default)
                        }
                    }
                }
        }
    }

    /**
     * Opens and starts [candidate]; returns the sound to try next when it fails, null when it plays. `MediaPlayer` reports
     * a bad source with several exception types; any of them means "try the next sound".
     */
    @Suppress("TooGenericExceptionCaught")
    private fun tryOpen(candidate: AlarmSound): AlarmSound? {
        val attempt = Opening(candidate)
        opening = attempt
        return try {
            val opened = playbacks.open(candidate, onPrepared = { onPrepared(attempt) }, onError = { onPlaybackError(attempt) })
            playback = opened
            sound = candidate
            applyGainLocked()
            if (!isPaused) opened.start()
            if (!attempt.prepared) startPrepareWatchdog(attempt)
            null
        } catch (e: Exception) {
            closeLocked()
            logger.log(LogEvent.SoundFellBack("could not open the sound: ${e::class.simpleName}"))
            fallbackAfter(candidate)
        }
    }

    private fun onPrepared(attempt: Opening) =
        synchronized(lock) {
            if (opening !== attempt) return@synchronized
            attempt.prepared = true
            attempt.preparedAt = monotonicClock.elapsedMillis()
            prepareWatchdog?.cancel()
            prepareWatchdog = null
            if (!isPaused) timings.stage(WakeStage.SoundStarted)
        }

    /** A sound that never reports prepared (a stalled content provider) counts as failed after [PREPARE_TIMEOUT]. */
    private fun startPrepareWatchdog(attempt: Opening) {
        prepareWatchdog?.cancel()
        prepareWatchdog =
            scope.launch {
                delay(PREPARE_TIMEOUT)
                synchronized(lock) {
                    if (opening === attempt && !attempt.prepared && playback != null) {
                        failLocked(attempt, "the sound did not prepare in time")
                    }
                }
            }
    }

    private fun onPlaybackError(attempt: Opening) =
        synchronized(lock) {
            if (opening === attempt && playback != null) failLocked(attempt, "the sound failed while ringing")
        }

    private fun failLocked(
        attempt: Opening,
        reason: String,
    ) {
        logger.log(LogEvent.SoundFellBack(reason))
        // A sound that played for a while counts as working: a long ring gets a fresh set of opens. Time spent
        // preparing does not count.
        val healthy = attempt.prepared && monotonicClock.elapsedMillis() - attempt.preparedAt >= HEALTHY_PLAY.inWholeMilliseconds
        if (healthy) opens = 0
        closeLocked()
        openLocked(fallbackAfter(attempt.sound))
    }

    /** Releases the open sound (the ring itself goes on). */
    private fun closeLocked() {
        prepareWatchdog?.cancel()
        prepareWatchdog = null
        playback?.release()
        playback = null
        opening = null
        sound = null
    }

    private fun resumeLocked() {
        isPaused = false
        playback?.start()
    }

    private fun applyGainLocked() {
        val rampStart = request?.rampStart
        val target =
            when {
                isMuted -> 0f
                rampStart == null || rampDone -> 1f
                else -> rampGain((monotonicClock.elapsedMillis() - rampStartedAt).milliseconds, rampStart).toFloat()
            }
        if (!isMuted && target >= 1f) rampDone = true
        gain = target
        playback?.setGain(target)
    }

    private fun startRampLoop() {
        rampJob?.cancel()
        rampJob =
            scope.launch {
                while (isActive && !synchronized(lock) { rampDone }) {
                    delay(RAMP_STEP)
                    updateGain()
                }
            }
    }

    private fun release() {
        rampJob?.cancel()
        rampJob = null
        retryJob?.cancel()
        retryJob = null
        closeLocked()
        request = null
        ringOn = false
        opens = 0
        isMuted = false
        isPaused = false
        gain = 0f
    }

    private companion object {
        val RAMP_STEP = 250.milliseconds
        val RETRY_DELAY = 5.seconds
        val HEALTHY_PLAY = 10.seconds
        val PREPARE_TIMEOUT = 5.seconds
        const val PERCENT = 100.0
        const val MAX_OPENS_PER_RING = 6
        const val EMERGENCY_REF = "emergency:default"

        /** A chosen sound falls back to the bundled default, the default to the phone's alarm ringtone, and that to the default. */
        fun fallbackAfter(sound: AlarmSound): AlarmSound =
            when (sound) {
                AlarmSound.Default -> AlarmSound.SystemAlarm
                AlarmSound.SystemAlarm, is AlarmSound.BuiltIn, is AlarmSound.File -> AlarmSound.Default
            }
    }
}

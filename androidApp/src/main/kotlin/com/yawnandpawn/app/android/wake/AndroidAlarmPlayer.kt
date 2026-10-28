package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.core.alarm.rampGain
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.log.WakeStage
import com.yawnandpawn.app.core.recording.MIX_SOUND_BLOCK
import com.yawnandpawn.app.core.recording.MixSource
import com.yawnandpawn.app.core.recording.Recording
import com.yawnandpawn.app.core.recording.mixSchedule
import com.yawnandpawn.app.core.time.MonotonicClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The only alarm player (AD-5), owned by [WakeRuntime]. It plays one looping sound on the alarm stream
 * ([ALARM_AUDIO_ATTRIBUTES], `USAGE_ALARM`), independent of media and ringer volume.
 *
 * - **Volume:** at the start of a ring, and again when a grace window ends ([unmuteTo]), it sets `STREAM_ALARM` to the
 *   alarm's volume ([AlarmVolume], which saves the user's own volume once); never continuously, so between those
 *   moments the user's change stays (Story 2.8). [stop] with `restoreVolume` puts the user's volume back at the end of
 *   the session.
 * - **Focus and calls (Story 2.7):** a ring requests audio focus ([AlarmAudioFocus]) and gives it back at [stop] (a
 *   ring that starts over keeps it); a focus change never ducks or pauses it. Only the session pauses it for a call,
 *   and a ring that starts during a call opens silent ([play] with `paused`) and asks for focus only when it resumes
 *   (so the call's app does not lose focus to a silent alarm). A refused request is asked again at the next resume.
 *   A resume after a call plays at full gain, with no ramp (the ramp's time is not spent in silence).
 * - **Ramp:** with "Gradually increase volume" on, the gain follows the pure `rampGain(elapsed, start, 30 s)`, updated
 *   about every 250 ms; off, the first frame plays at full gain. Elapsed time is monotonic.
 * - **Never silent:** a sound that cannot be opened, or fails during prepare or while ringing, is replaced in the same
 *   ring: a chosen sound by the bundled default, the default by the phone's alarm ringtone (and back), a bounded number
 *   of opens at a time (a sound that played 10 s resets the count). When every open failed, the default is tried again
 *   every 5 s while the ring lasts. [resolver] maps the alarm's sound (a library sound or a phone ringtone, Story 1.17);
 *   an unknown sound reference plays the default. Each fallback is logged without the sound reference or its URI.
 *   Sounds prepare asynchronously (`MediaPlayerPlaybackFactory`); a prepare error, or a sound not prepared within 5 s
 *   (a stalled content provider), falls back like a playback error. A new ring stops the Sound preview ([onRingStart]).
 * - **Mix into alarm (Story 7.5):** a ring with a [MixRequest] alternates the alarm sound with the session's recording
 *   per the pure `mixSchedule`: 30 s of alarm sound (ramping as above), then the recording once, repeating. The alarm
 *   sound never stops: its gain goes to 0 while the recording plays, so the never-silent ladder, focus and wake lock stay
 *   in charge and switching back is instant. The recording is found off the caller's thread ([recordingSource] on
 *   [scope]) and opened once ([recordingPlaybacks], `USAGE_ALARM`, full gain on the ring's alarm-stream volume). The
 *   ramp loop checks the schedule every 250 ms from the mix anchor: the ring's start, and again after the recording,
 *   after grace ([unmuteTo]) and after a call ([resume]). Grace and calls silence both and pause the recording. A missing
 *   recording, an open or prepare error, a prepare not done within 5 s or an error while playing leaves the alarm sound
 *   alone at full gain for the rest of that session, also on its later rings (the session is remembered); logged by
 *   error code only.
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
    /** The ring's audio focus (Story 2.7): requested when a ring opens, given back when it is released. */
    private val focus: AlarmAudioFocus? = null,
    /** Opens the recording of a "Mix into alarm" ring (Story 7.5). */
    private val recordingPlaybacks: RecordingPlaybackFactory? = null,
    /**
     * The readable file of a recording id, or null when its row or file is missing or cannot be read (storage locked).
     * Runs on [scope], never under the player's lock; it must not call back into the player.
     */
    private val recordingSource: suspend (recordingId: String) -> File? = { null },
) {
    /**
     * What the session asked for; a different request starts the ring over. [rampStart] is null when there is no ramp,
     * [mix] when the ring does not mix a recording in.
     */
    private data class Request(
        val soundRef: String,
        val volumePercent: Int,
        val rampStart: Double?,
        val mix: MixRequest? = null,
    )

    /** The recording a ring mixes in (Story 7.5); callbacks of a released one are told apart by identity. */
    private class Mixing(
        val request: MixRequest,
    ) {
        var playback: RecordingPlayback? = null
        var prepared = false

        /** Its length, known once it is prepared and open; 0 before. */
        var durationMs = 0L

        /** The recording plays now (the alarm sound is at gain 0). */
        var playing = false

        /** Where [mixSchedule] counts from (monotonic). */
        var anchor = 0L
        var loadJob: Job? = null
        var watchdog: Job? = null
    }

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
    private var mixing: Mixing? = null

    /** The session whose recording failed (Story 7.5): its later rings play the alarm sound alone. */
    private var failedMixSession: String? = null

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

    /** The ring's recording plays now ("Mix into alarm", Story 7.5): the alarm sound is held at gain 0 meanwhile. */
    val isMixPlaying: Boolean
        get() = synchronized(lock) { mixing?.playing == true }

    /** The ring mixes a recording in: one is loading, prepared or playing (false once it failed, Story 7.5). */
    val isMixing: Boolean
        get() = synchronized(lock) { mixing != null }

    /** The open sound has prepared (so it plays unless paused or muted); false while preparing or with nothing open. */
    val isPrepared: Boolean
        get() = synchronized(lock) { playback != null && opening?.prepared == true }

    /**
     * Plays [soundRef] at [volumePercent] of the alarm stream, ramping from [rampStartPercent] of it when [gradual], and
     * unmuted. The same request again only unmutes and resumes; another one starts the ring over. With [paused] (a call
     * is in progress, Story 2.7) the ring opens but stays silent: it never starts, so not one frame is audible, and the
     * same request again keeps it paused. With [mix] ("Mix into alarm", Story 7.5) the ring alternates the sound with
     * that recording.
     */
    fun play(
        soundRef: String,
        volumePercent: Int,
        gradual: Boolean,
        rampStartPercent: Int,
        paused: Boolean = false,
        mix: MixRequest? = null,
    ) = synchronized(lock) {
        val wanted = Request(soundRef, volumePercent, if (gradual) rampStartPercent / PERCENT else null, mix)
        val wasMuted = isMuted
        isMuted = false
        if (playback != null && wanted == request) {
            when {
                paused && !isPaused -> pauseLocked()
                !paused && isPaused -> resumeLocked()
                wasMuted -> restartMixLocked()
            }
            applyGainLocked()
        } else {
            startRingLocked(wanted, resolve(soundRef), paused)
        }
    }

    /** The emergency ring: the bundled default at [volumePercent], full gain at once; silent with [paused] (a call). */
    fun playDefault(
        volumePercent: Int,
        paused: Boolean = false,
    ) = synchronized(lock) {
        if (request?.soundRef == EMERGENCY_REF && playback != null) return@synchronized
        startRingLocked(Request(EMERGENCY_REF, volumePercent, null), AlarmSound.Default, paused)
    }

    /**
     * After a crash in the wake flow (AD-12): the open sound becomes the default, keeping mute, pause and gain. A mixed-in
     * recording is released (Story 7.5): the default plays alone.
     */
    fun switchToDefault() =
        synchronized(lock) {
            if (request == null) return@synchronized
            if (mixing != null) {
                releaseMixLocked()
                applyGainLocked()
            }
            if (sound == AlarmSound.Default && playback != null) return@synchronized
            closeLocked()
            opens = 0
            openLocked(AlarmSound.Default)
        }

    /** The grace window: silent, still open; a recording that plays pauses (Story 7.5). */
    fun mute() =
        synchronized(lock) {
            isMuted = true
            stopMixPhaseLocked()
            applyGainLocked()
        }

    /**
     * The grace window ended (Story 2.8, FR-SES-6): the alarm stream is set to [volumePercent] again (the user may have
     * turned it down meanwhile) and the sound is back at full gain. Once per grace end, never continuously.
     */
    fun unmuteTo(volumePercent: Int) =
        synchronized(lock) {
            if (request != null) volume.setForRing(volumePercent)
            isMuted = false
            rampDone = true
            restartMixLocked()
            applyGainLocked()
        }

    /**
     * Sets the alarm stream back to the playing ring's volume once (Story 2.8): the hook for a purchase flow handing the
     * screen back (Spike S1: the volume keys work on the Play sheet). Nothing while no ring is open, muted or paused.
     */
    fun reassertVolume() =
        synchronized(lock) {
            val playing = request ?: return@synchronized
            if (!isMuted && !isPaused) volume.setForRing(playing.volumePercent)
        }

    /** A call: paused until [resume] (or the next [play]). */
    fun pause() = synchronized(lock) { pauseLocked() }

    fun resume() = synchronized(lock) { if (isPaused) resumeLocked() }

    /** Stops and releases the sound; with [restoreVolume] (the session ended) the user's alarm volume comes back. */
    fun stop(restoreVolume: Boolean) =
        synchronized(lock) {
            release()
            focus?.abandon()
            if (restoreVolume) volume.restore()
        }

    /**
     * At app start with no session: puts back a user volume a crashed session left saved, unless a ring started
     * meanwhile (checked under the player's lock, so a cold-start ring is never turned down).
     */
    fun restoreVolumeIfSilent() = synchronized(lock) { if (request == null) volume.restore() }

    /**
     * One ramp step: the gain for the time since the ring started, and the mix schedule's source (Story 7.5). The ramp
     * loop calls it; tests may too.
     */
    fun updateGain() =
        synchronized(lock) {
            applyGainLocked()
            mixStepLocked()
        }

    private fun startRingLocked(
        wanted: Request,
        first: AlarmSound,
        paused: Boolean = false,
    ) {
        // Focus stays held across a ring that starts over (released only at [stop]); one that opens paused asks at resume.
        release()
        request = wanted
        ringOn = true
        isPaused = paused
        onRingStart()
        if (!paused) focus?.request()
        volume.setForRing(wanted.volumePercent)
        rampStartedAt = monotonicClock.elapsedMillis()
        rampDone = wanted.rampStart == null
        openLocked(first)
        startMixLocked(wanted.mix)
        if (!rampDone || mixing != null) startRampLoop()
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

    private fun pauseLocked() {
        isPaused = true
        stopMixPhaseLocked()
        playback?.pause()
    }

    /**
     * After a call (Story 2.7): focus again (deferred, or refused during the call), the set volume at full gain with no
     * ramp, and the ring-start timing of a ring that opened silent and is prepared (logged once per fire).
     */
    private fun resumeLocked() {
        isPaused = false
        focus?.request()
        rampDone = true
        restartMixLocked()
        applyGainLocked()
        playback?.start()
        if (opening?.prepared == true) timings.stage(WakeStage.SoundStarted)
    }

    private fun applyGainLocked() {
        val rampStart = request?.rampStart
        val target =
            when {
                isMuted -> 0f

                // The recording plays (Story 7.5): the alarm sound loops on silently, so switching back has no gap.
                mixing?.playing == true -> 0f

                rampStart == null || rampDone -> 1f

                else -> rampGain((monotonicClock.elapsedMillis() - rampStartedAt).milliseconds, rampStart).toFloat()
            }
        if (!isMuted && mixing?.playing != true && target >= 1f) rampDone = true
        gain = target
        playback?.setGain(target)
    }

    private fun startRampLoop() {
        rampJob?.cancel()
        rampJob =
            scope.launch {
                // The loop also drives the mix schedule for as long as the ring mixes a recording in (Story 7.5).
                while (isActive && synchronized(lock) { !rampDone || mixing != null }) {
                    delay(RAMP_STEP)
                    updateGain()
                }
            }
    }

    /**
     * Starts mixing [mix]'s recording into the ring that starts now (Story 7.5), unless there is none, no factory, or its
     * session's recording already failed. The file is found on [scope] (never under the lock or on the caller's thread);
     * a recording not prepared within [PREPARE_TIMEOUT] of the ring's start fails.
     */
    private fun startMixLocked(mix: MixRequest?) {
        if (mix == null || recordingPlaybacks == null || mix.sessionId == failedMixSession) return
        val started = Mixing(mix).apply { anchor = monotonicClock.elapsedMillis() }
        mixing = started
        started.loadJob =
            scope.launch {
                val file = findRecording(mix.recordingId)
                synchronized(lock) { if (mixing === started) openMixLocked(started, file) }
            }
        started.watchdog =
            scope.launch {
                delay(PREPARE_TIMEOUT)
                synchronized(lock) {
                    if (mixing === started && !started.prepared) failMixLocked("the message did not prepare in time")
                }
            }
    }

    // Whatever the source throws means "no recording": the alarm sound plays alone.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun findRecording(recordingId: String): File? =
        try {
            recordingSource(recordingId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(LogEvent.OperationFailed(MIX_OPERATION, e::class.simpleName.orEmpty()))
            null
        }

    /** Opens the found [file] of [started] (null: missing), once; its prepare ends the watchdog. */
    @Suppress("TooGenericExceptionCaught")
    private fun openMixLocked(
        started: Mixing,
        file: File?,
    ) {
        val factory = recordingPlaybacks
        if (file == null || factory == null) {
            failMixLocked("the message is missing")
            return
        }
        try {
            val opened =
                factory.open(
                    file,
                    onPrepared = { onMixPrepared(started) },
                    onCompleted = { onMixCompleted(started) },
                    onError = { onMixError(started) },
                )
            if (mixing !== started) {
                // It failed while opening (an error reported before open returned).
                opened.release()
                return
            }
            started.playback = opened
            if (started.prepared) mixReadyLocked(started)
        } catch (e: Exception) {
            failMixLocked("could not open the message: ${e::class.simpleName}")
        }
    }

    private fun onMixPrepared(started: Mixing) =
        synchronized(lock) {
            if (mixing !== started) return@synchronized
            started.prepared = true
            if (started.playback != null) mixReadyLocked(started)
        }

    /** [started] is prepared and open: its length is known (a recording without one fails), the watchdog ends. */
    private fun mixReadyLocked(started: Mixing) {
        val length = started.playback?.durationMs ?: 0L
        if (length <= 0L) {
            failMixLocked("the message has no length")
            return
        }
        // A wrong header never holds the alarm sound at gain 0 for longer than the longest recording.
        started.durationMs = length.coerceAtMost(Recording.MAX_DURATION_MS)
        started.watchdog?.cancel()
        started.watchdog = null
        if (recordingCall(started) { it.setGain(FULL_GAIN) }) mixStepLocked()
    }

    /** The recording played to its end: the alarm sound is back at full gain and the schedule counts from now. */
    private fun onMixCompleted(started: Mixing) =
        synchronized(lock) {
            if (mixing !== started || !started.playing) return@synchronized
            started.playing = false
            started.anchor = monotonicClock.elapsedMillis()
            applyGainLocked()
        }

    private fun onMixError(started: Mixing) =
        synchronized(lock) {
            if (mixing === started) failMixLocked("the message failed while playing")
        }

    /**
     * What the mix schedule wants now: the recording (it starts from 0 and the alarm sound goes to gain 0) or the alarm
     * sound (back at full gain, the anchor restarts). Nothing while muted, paused or before the recording is ready. The
     * recording's own completion ends its slot ([onMixCompleted]); the schedule's slot is [MIX_SLACK] longer than the
     * recording, so start latency never cuts its end, and only a completion that never comes is ended by the schedule.
     */
    private fun mixStepLocked() {
        val current = mixing ?: return
        if (request == null || isMuted || isPaused) return
        val now = monotonicClock.elapsedMillis()
        val length = if (current.playback != null && current.prepared) current.durationMs else 0L
        val slot = if (length > 0) length + MIX_SLACK.inWholeMilliseconds else 0L
        val wanted = mixSchedule((now - current.anchor).milliseconds, recordingDuration = slot.milliseconds)
        when {
            wanted == MixSource.Recording && !current.playing && length > 0 -> startRecordingLocked(current, now)
            wanted == MixSource.Alarm && current.playing -> backToAlarmLocked(current, now)
        }
    }

    /** The recording starts from 0 (only once its start succeeded), and the alarm sound goes to gain 0. */
    private fun startRecordingLocked(
        current: Mixing,
        now: Long,
    ) {
        if (recordingCall(current) { it.setGain(FULL_GAIN) } && recordingCall(current) { it.start() }) {
            current.playing = true
            // Counted from the start of the recording's slot, so a recording that became ready late still plays whole.
            current.anchor = now - MIX_SOUND_BLOCK.inWholeMilliseconds
            applyGainLocked()
        }
    }

    /** The recording's slot is over without a completion report: it pauses, and the alarm sound is back at full gain. */
    private fun backToAlarmLocked(
        current: Mixing,
        now: Long,
    ) {
        current.playing = false
        if (recordingCall(current) { it.pause() }) {
            current.anchor = now
            applyGainLocked()
        }
    }

    /** Grace or a call: a recording that plays pauses; the alarm sound's gain is the caller's. */
    private fun stopMixPhaseLocked() {
        val current = mixing ?: return
        if (current.playing) {
            current.playing = false
            recordingCall(current) { it.pause() }
        }
    }

    /**
     * Runs [call] on [current]'s recording; false when it threw (a `MediaPlayer` already in its Error state throws before
     * its error report arrives), and the mix then fails: the alarm sound plays alone at full gain.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun recordingCall(
        current: Mixing,
        call: (RecordingPlayback) -> Unit,
    ): Boolean =
        try {
            current.playback?.let(call)
            true
        } catch (e: Exception) {
            if (mixing === current) failMixLocked("the message failed: ${e::class.simpleName}")
            false
        }

    /** Grace ended or a call is over: the alarm sound comes first again, the schedule counts from now. */
    private fun restartMixLocked() {
        val current = mixing ?: return
        stopMixPhaseLocked()
        current.anchor = monotonicClock.elapsedMillis()
    }

    /**
     * The recording failed (FR-SND-5): released at once, the alarm sound back at full gain, and the session's later rings
     * play the alarm sound alone. Logged by error code only.
     */
    private fun failMixLocked(reason: String) {
        val failed = mixing ?: return
        logger.log(LogEvent.OperationFailed(MIX_OPERATION, reason))
        failedMixSession = failed.request.sessionId
        releaseMixLocked()
        applyGainLocked()
    }

    private fun releaseMixLocked() {
        val current = mixing ?: return
        mixing = null
        current.loadJob?.cancel()
        current.watchdog?.cancel()
        current.playing = false
        current.playback?.release()
        current.playback = null
    }

    /** Ends the ring's sound; the audio focus is the caller's ([stop] gives it back). */
    private fun release() {
        rampJob?.cancel()
        rampJob = null
        retryJob?.cancel()
        retryJob = null
        releaseMixLocked()
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
        const val FULL_GAIN = 1f

        /** How much longer than the recording its slot lasts before the schedule ends it without a completion report. */
        val MIX_SLACK = 1.seconds
        const val MIX_OPERATION = "mix motivation"
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

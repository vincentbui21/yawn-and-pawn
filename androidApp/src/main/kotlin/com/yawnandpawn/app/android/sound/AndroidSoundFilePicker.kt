package com.yawnandpawn.app.android.sound

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.ui.sound.SoundFilePicker
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Android [SoundFilePicker] (Story 7.4, the `AndroidMicrophonePermission` pattern): the system document picker
 * (`ACTION_OPEN_DOCUMENT`, audio files only) is launched by the screen in front. `MainActivity` attaches its launcher
 * ([attach]) while it is started and hands the picked URI (null when cancelled) to [onResult]. Unlike a permission
 * dialog the picker covers the app, which then stops: a stop detaches the launcher but keeps the pick waiting, and the
 * answer reaches [onResult] from the activity that is started when the picker closes (the same one, or a recreated
 * one). With no screen attached nothing is opened and the answer is null. Nothing logged names the file or its URI.
 */
class AndroidSoundFilePicker(
    private val logger: Logger,
) : SoundFilePicker {
    @Volatile
    private var launcher: (() -> Unit)? = null

    @Volatile
    private var pending: CompletableDeferred<String?>? = null

    /** The screen in front can open the picker through [launch] (the newest attach wins). */
    @Synchronized
    fun attach(launch: () -> Unit) {
        launcher = launch
    }

    /** The screen that attached [launch] stopped; a newer screen's launcher stays, and a pick in progress keeps waiting. */
    @Synchronized
    fun detach(launch: () -> Unit) {
        if (launcher === launch) launcher = null
    }

    /** The picker closed: the picked file's [uri], or null when the user backed out. */
    @Synchronized
    fun onResult(uri: String?) {
        pending?.complete(uri)
        pending = null
    }

    override suspend fun pick(): String? {
        val answer = open()
        if (answer == null) logger.log(LogEvent.OperationFailed("pick sound file", "no screen in front"))
        return try {
            answer?.await()
        } catch (e: CancellationException) {
            // The pick was abandoned (the editor closed): a lost answer must not hold every later pick.
            forget(answer)
            throw e
        }
    }

    /** Drops [answer] as the pending pick, unless a newer one replaced it. */
    @Synchronized
    private fun forget(answer: CompletableDeferred<String?>?) {
        if (pending === answer) pending = null
    }

    /**
     * The answer of the picker opened now (one at a time: a second pick waits on the same one); null with no screen. A
     * phone with no document picker (`ActivityNotFoundException`), or an activity going away (`IllegalStateException`),
     * answers null at once: either means "nothing picked".
     */
    @Synchronized
    private fun open(): CompletableDeferred<String?>? {
        val opener = launcher
        return when {
            pending != null -> pending
            opener == null -> null
            else -> launchWith(opener)
        }
    }

    /** Opens the picker through [launch] and keeps its answer pending; a launch that fails answers null at once. */
    @Suppress("TooGenericExceptionCaught")
    private fun launchWith(launch: () -> Unit): CompletableDeferred<String?> {
        val answer = CompletableDeferred<String?>()
        pending = answer
        try {
            launch()
        } catch (e: RuntimeException) {
            logger.log(LogEvent.OperationFailed("pick sound file", "picker not opened: ${e::class.simpleName}"))
            pending = null
            answer.complete(null)
        }
        return answer
    }
}

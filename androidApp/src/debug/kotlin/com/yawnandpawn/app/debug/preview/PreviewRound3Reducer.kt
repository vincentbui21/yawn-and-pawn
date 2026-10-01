package com.yawnandpawn.app.debug.preview

import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checksetup.CheckPreviewUiState
import com.yawnandpawn.app.ui.househunt.HouseHuntIntent
import com.yawnandpawn.app.ui.househunt.HouseHuntRegistrationUiState
import com.yawnandpawn.app.ui.recordings.RecordedMessage
import com.yawnandpawn.app.ui.recordings.RecorderState
import com.yawnandpawn.app.ui.recordings.RecordingsIntent
import com.yawnandpawn.app.ui.recordings.RecordingsUiState
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.HouseHuntResult
import com.yawnandpawn.app.ui.wake.MemoryPhase
import com.yawnandpawn.app.ui.wake.WakeIntent

/** The check "Try it" opens: Math 47 + 38, a word, Memory Sequence on your turn, or a camera check. */
internal fun tryItFor(type: CheckType): CheckPreviewUiState =
    when (type) {
        CheckType.Math -> {
            PreviewSetupSamples.tryMath
        }

        CheckType.WordUnscramble -> {
            PreviewSetupSamples.tryWord
        }

        CheckType.MemorySequence -> {
            CheckPreviewUiState(
                CheckContent.MemorySequence(round = 1, roundCount = 3, phase = MemoryPhase.YourTurn),
            )
        }

        CheckType.QrBarcode -> {
            CheckPreviewUiState(CheckContent.QrBarcode())
        }

        CheckType.HouseHunt -> {
            CheckPreviewUiState(CheckContent.HouseHunt())
        }
    }

/**
 * "Try it" responds like the check: Math takes digits and is solved by the right answer (85), a wrong one clears the
 * field; letters move into the next empty slot until the word is full; three tiles or the shutter finish the others.
 * [taps] counts every input so far.
 */
internal fun reduceTryIt(
    state: CheckPreviewUiState,
    intent: WakeIntent,
    taps: Int,
): CheckPreviewUiState =
    when (val content = state.content) {
        is CheckContent.Math -> {
            state.withMath(content, intent)
        }

        is CheckContent.WordUnscramble -> {
            if (intent is WakeIntent.LetterTapped) state.withLetterPlaced(content, intent.index) else state
        }

        is CheckContent.MemorySequence -> {
            if (intent is WakeIntent.TileTapped && taps >= MEMORY_TAPS) state.copy(done = true) else state
        }

        is CheckContent.HouseHunt -> {
            if (intent == WakeIntent.ShutterClicked) state.copy(done = true) else state
        }

        is CheckContent.QrBarcode -> {
            state
        }
    }

private fun CheckPreviewUiState.withMath(
    content: CheckContent.Math,
    intent: WakeIntent,
): CheckPreviewUiState =
    when (intent) {
        is WakeIntent.DigitTapped -> {
            copy(content = content.copy(answer = (content.answer + intent.digit).take(MAX_DIGITS), wrong = false))
        }

        WakeIntent.DeleteDigit -> {
            copy(content = content.copy(answer = content.answer.dropLast(1)))
        }

        WakeIntent.SubmitAnswer -> {
            if (content.answer.toIntOrNull() == content.left + content.right) {
                copy(done = true)
            } else {
                copy(content = content.copy(answer = "", wrong = true))
            }
        }

        else -> {
            this
        }
    }

private fun CheckPreviewUiState.withLetterPlaced(
    content: CheckContent.WordUnscramble,
    index: Int,
): CheckPreviewUiState {
    val letter = content.pool.getOrNull(index)
    val slot = content.slots.indexOfFirst { it == null }
    if (letter == null || slot < 0) return this
    val slots = content.slots.toMutableList().also { it[slot] = letter }
    val pool = content.pool.toMutableList().also { it[index] = null }
    return copy(content = content.copy(pool = pool, slots = slots), done = slots.none { it == null })
}

/** House Hunt registration: the shutter adds a photo (up to 3), "Remove" takes one away, "Test match" matches. */
internal fun reduceHouseHunt(
    state: HouseHuntRegistrationUiState,
    intent: HouseHuntIntent,
): HouseHuntRegistrationUiState =
    when (intent) {
        HouseHuntIntent.ShutterClicked -> {
            state.copy(
                photos = (state.photos + 1).coerceAtMost(HouseHuntRegistrationUiState.MAX_PHOTOS),
                error = null,
                testResult = HouseHuntResult.None,
            )
        }

        is HouseHuntIntent.RemovePhoto -> {
            state.copy(photos = (state.photos - 1).coerceAtLeast(0), testResult = HouseHuntResult.None)
        }

        HouseHuntIntent.TestMatchClicked -> {
            state.copy(testResult = HouseHuntResult.Matched)
        }

        HouseHuntIntent.FixCamera -> {
            state.copy(cameraUnavailable = false)
        }

        HouseHuntIntent.SaveClicked, HouseHuntIntent.Cancel -> {
            state
        }
    }

/**
 * Recordings: recording shows a fixed 0:09 take (nothing records), stop keeps it, "Save" adds "Message {n}", play
 * toggles one message at a time, delete asks first. Back is the caller's.
 */
internal fun reduceRecordings(
    state: RecordingsUiState,
    intent: RecordingsIntent,
): RecordingsUiState =
    when (intent) {
        RecordingsIntent.RecordClicked, RecordingsIntent.ReRecordClicked -> {
            PreviewSetupSamples.recordingsRecording.copy(messages = state.messages.map { it.copy(playing = false) })
        }

        RecordingsIntent.StopClicked -> {
            state.copy(recorder = RecorderState.Recorded(seconds = TAKE_SECONDS))
        }

        RecordingsIntent.TakePlayToggled -> {
            val take = state.recorder as? RecorderState.Recorded
            if (take == null) state else state.copy(recorder = take.copy(playing = !take.playing))
        }

        RecordingsIntent.SaveTakeClicked -> {
            val number = (state.messages.maxOfOrNull { it.number } ?: 0) + 1
            state.copy(messages = state.messages + RecordedMessage(number, TAKE_SECONDS), recorder = RecorderState.Idle)
        }

        RecordingsIntent.DeleteTakeClicked -> {
            state.copy(recorder = RecorderState.Idle)
        }

        else -> {
            reduceSavedMessages(state, intent)
        }
    }

/** Play, delete and the microphone fix on the saved messages. */
private fun reduceSavedMessages(
    state: RecordingsUiState,
    intent: RecordingsIntent,
): RecordingsUiState =
    when (intent) {
        is RecordingsIntent.PlayToggled -> {
            state.copy(
                messages =
                    state.messages.map {
                        val playing = it.number == intent.number && !it.playing
                        it.copy(playing = playing, playedSeconds = if (playing) PLAYED_SECONDS else 0)
                    },
            )
        }

        is RecordingsIntent.DeleteClicked -> {
            state.copy(deleteDialogFor = intent.number)
        }

        RecordingsIntent.DeleteConfirmed -> {
            state.copy(messages = state.messages.filterNot { it.number == state.deleteDialogFor }, deleteDialogFor = null)
        }

        RecordingsIntent.DeleteCancelled -> {
            state.copy(deleteDialogFor = null)
        }

        RecordingsIntent.FixMicrophone -> {
            state.copy(micDenied = false)
        }

        else -> {
            state
        }
    }

private const val MAX_DIGITS = 4
private const val MEMORY_TAPS = 3
private const val TAKE_SECONDS = 9
private const val PLAYED_SECONDS = 4

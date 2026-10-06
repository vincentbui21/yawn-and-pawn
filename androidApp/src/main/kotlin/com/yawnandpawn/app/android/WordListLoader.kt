package com.yawnandpawn.app.android

import android.content.Context
import com.yawnandpawn.app.core.checks.word.WordList
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger

/**
 * Reads the Word Unscramble list from the bundled asset `words_en.txt` (Story 3.7). An APK asset needs no
 * credential-protected storage, so this works before the first unlock (Direct Boot). About 1,400 short lines: a few
 * milliseconds, read once at app start.
 */
object WordListLoader {
    const val ASSET = "words_en.txt"

    /**
     * The bundled list, or an empty one when the asset cannot be read, logged on [logger] (review fix): it runs before
     * anything else at process start, so a missing or broken asset must never crash the app and stop every alarm. With
     * no list a ring freezes Math in place of Word Unscramble (`ConfigResolver`), so no check is ever unsolvable.
     */
    fun load(
        context: Context,
        logger: Logger,
    ): WordList =
        runCatching {
            WordList(
                context.assets
                    .open(ASSET)
                    .bufferedReader()
                    .use { it.readLines() },
            )
        }.getOrElse { failure ->
            logger.log(LogEvent.OperationFailed("load word list", failure.message ?: failure::class.simpleName.orEmpty()))
            WordList(emptyList())
        }
}

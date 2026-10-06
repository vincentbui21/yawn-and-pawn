package com.yawnandpawn.app.android

import android.content.Context
import com.yawnandpawn.app.core.checks.word.WordList

/**
 * Reads the Word Unscramble list from the bundled asset `words_en.txt` (Story 3.7). An APK asset needs no
 * credential-protected storage, so this works before the first unlock (Direct Boot). About 1,400 short lines: a few
 * milliseconds, read once at app start.
 */
object WordListLoader {
    const val ASSET = "words_en.txt"

    fun load(context: Context): WordList =
        WordList(
            context.assets
                .open(ASSET)
                .bufferedReader()
                .use { it.readLines() },
        )
}

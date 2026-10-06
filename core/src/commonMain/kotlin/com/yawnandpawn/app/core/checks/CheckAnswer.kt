package com.yawnandpawn.app.core.checks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the user submitted for the current check item (AD-9). Each check story adds the answer of its type. */
@Serializable
sealed interface CheckAnswer {
    /** The answer to a [CheckType.Placeholder] step. */
    @Serializable
    @SerialName("Placeholder")
    data object Placeholder : CheckAnswer

    /** The image matcher matched the photo (House Hunt); it re-enters as `ImageMatchCompleted(matched = true)`. */
    @Serializable
    @SerialName("ImageMatched")
    data object ImageMatched : CheckAnswer

    /**
     * A number typed on the pad ([CheckType.Math]), exactly as typed. Only the type decides whether it is right: leading
     * zeros are ignored, and anything that is not a non-empty run of digits is wrong.
     */
    @Serializable
    @SerialName("Number")
    data class Number(
        val digits: String,
    ) : CheckAnswer

    /** A word spelled from the letters of a [CheckType.WordUnscramble] item (any case). */
    @Serializable
    @SerialName("Word")
    data class Word(
        val text: String,
    ) : CheckAnswer

    /** One tile tapped on a [CheckType.MemorySequence] grid, numbered from 1 row by row. */
    @Serializable
    @SerialName("Tile")
    data class Tile(
        val number: Int,
    ) : CheckAnswer
}

package com.yawnandpawn.app.ui.checks

import androidx.compose.runtime.Composable
import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.check_count_problem
import com.yawnandpawn.app.ui.resources.check_count_problems
import com.yawnandpawn.app.ui.resources.check_count_problems_label
import com.yawnandpawn.app.ui.resources.check_count_round
import com.yawnandpawn.app.ui.resources.check_count_rounds
import com.yawnandpawn.app.ui.resources.check_count_rounds_label
import com.yawnandpawn.app.ui.resources.check_count_word
import com.yawnandpawn.app.ui.resources.check_count_words
import com.yawnandpawn.app.ui.resources.check_count_words_label
import com.yawnandpawn.app.ui.resources.check_house_hunt
import com.yawnandpawn.app.ui.resources.check_house_hunt_description
import com.yawnandpawn.app.ui.resources.check_math
import com.yawnandpawn.app.ui.resources.check_math_description
import com.yawnandpawn.app.ui.resources.check_memory
import com.yawnandpawn.app.ui.resources.check_memory_description
import com.yawnandpawn.app.ui.resources.check_qr
import com.yawnandpawn.app.ui.resources.check_qr_description
import com.yawnandpawn.app.ui.resources.check_word
import com.yawnandpawn.app.ui.resources.check_word_description
import com.yawnandpawn.app.ui.resources.difficulty_easy
import com.yawnandpawn.app.ui.resources.difficulty_hard
import com.yawnandpawn.app.ui.resources.difficulty_medium
import com.yawnandpawn.app.ui.resources.symbol_calculate
import com.yawnandpawn.app.ui.resources.symbol_grid_view
import com.yawnandpawn.app.ui.resources.symbol_house
import com.yawnandpawn.app.ui.resources.symbol_qr_code_scanner
import com.yawnandpawn.app.ui.resources.symbol_sort_by_alpha
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import com.yawnandpawn.app.core.checks.CheckType as CoreCheckType
import com.yawnandpawn.app.core.checks.Difficulty as CoreDifficulty

/** The five proof-of-wake checks (EXPERIENCE.md glossary), in the order pickers list them. */
enum class CheckType {
    Math,
    WordUnscramble,
    MemorySequence,
    QrBarcode,
    HouseHunt,
    ;

    /** QR/Barcode and House Hunt need the camera; they are never offered as a fallback check. */
    val usesCamera: Boolean get() = this == QrBarcode || this == HouseHunt
}

/** Check difficulty (Check setup, `chip-check`). */
enum class Difficulty { Easy, Medium, Hard }

/** The checks with a difficulty and a count (Math, Word Unscramble, Memory Sequence); the camera checks have neither. */
val CheckType.hasDifficulty: Boolean get() = !usesCamera

/** How many problems, words or rounds a new check starts with (0 for the camera checks). */
val CheckType.defaultCount: Int
    get() =
        when (this) {
            CheckType.Math, CheckType.WordUnscramble -> 2
            CheckType.MemorySequence -> 3
            CheckType.QrBarcode, CheckType.HouseHunt -> 0
        }

/** The Check setup count label: Math "Problems", Word Unscramble "Words", Memory Sequence "Rounds" (EXPERIENCE.md). */
fun CheckType.countLabel(): StringResource? =
    when (this) {
        CheckType.Math -> Res.string.check_count_problems_label
        CheckType.WordUnscramble -> Res.string.check_count_words_label
        CheckType.MemorySequence -> Res.string.check_count_rounds_label
        CheckType.QrBarcode, CheckType.HouseHunt -> null
    }

/** "2 problems", "1 word", "3 rounds"; empty for the camera checks. */
@Composable
fun CheckType.countText(count: Int): String {
    val (one, many) =
        when (this) {
            CheckType.Math -> Res.string.check_count_problem to Res.string.check_count_problems
            CheckType.WordUnscramble -> Res.string.check_count_word to Res.string.check_count_words
            CheckType.MemorySequence -> Res.string.check_count_round to Res.string.check_count_rounds
            CheckType.QrBarcode, CheckType.HouseHunt -> return ""
        }
    return if (count == 1) stringResource(one) else stringResource(many, count)
}

/** The check's name, as the glossary spells it ("Word Unscramble", "QR/Barcode"). */
@Composable
fun CheckType.displayName(): String =
    stringResource(
        when (this) {
            CheckType.Math -> Res.string.check_math
            CheckType.WordUnscramble -> Res.string.check_word
            CheckType.MemorySequence -> Res.string.check_memory
            CheckType.QrBarcode -> Res.string.check_qr
            CheckType.HouseHunt -> Res.string.check_house_hunt
        },
    )

/** The one-line card description (EXPERIENCE.md Check picker, card descriptions). */
@Composable
fun CheckType.description(): String =
    stringResource(
        when (this) {
            CheckType.Math -> Res.string.check_math_description
            CheckType.WordUnscramble -> Res.string.check_word_description
            CheckType.MemorySequence -> Res.string.check_memory_description
            CheckType.QrBarcode -> Res.string.check_qr_description
            CheckType.HouseHunt -> Res.string.check_house_hunt_description
        },
    )

/** Material Symbols Rounded icon of the check (`card-alarm` check icons, `check-type-card`). */
val CheckType.icon: DrawableResource
    get() =
        when (this) {
            CheckType.Math -> Res.drawable.symbol_calculate
            CheckType.WordUnscramble -> Res.drawable.symbol_sort_by_alpha
            CheckType.MemorySequence -> Res.drawable.symbol_grid_view
            CheckType.QrBarcode -> Res.drawable.symbol_qr_code_scanner
            CheckType.HouseHunt -> Res.drawable.symbol_house
        }

/** The core plugin of this check (AD-9), or null while its story has not added one (the camera checks so far). */
val CheckType.core: CoreCheckType?
    get() =
        when (this) {
            CheckType.Math -> CoreCheckType.Math
            CheckType.WordUnscramble -> CoreCheckType.WordUnscramble
            CheckType.MemorySequence -> CoreCheckType.MemorySequence()
            CheckType.QrBarcode, CheckType.HouseHunt -> null
        }

/**
 * The UI check of a core plugin, by its stable id (so both Memory Sequence variants are Memory Sequence), or null for
 * one a user never sees (the Epic 1 placeholder).
 */
fun CoreCheckType.toUi(): CheckType? = CheckType.entries.firstOrNull { it.core?.id == id }

fun Difficulty.toCore(): CoreDifficulty = CoreDifficulty.valueOf(name)

fun CoreDifficulty.toUi(): Difficulty = Difficulty.valueOf(name)

/**
 * The checks the pickers offer (Story 3.5): those with a core plugin a user may pick (`CheckConfig.PICKABLE_TYPES`) and a
 * registered "Try it" ([CheckRegistry], Story 3.6), in the picker order. The wake screen joins the registry with 3.2.
 */
val PickableCheckTypes: List<CheckType>
    get() = CheckType.entries.filter { type -> type.core?.let { it in CheckConfig.PICKABLE_TYPES } == true && type in CheckRegistry.types }

@Composable
fun Difficulty.displayName(): String =
    stringResource(
        when (this) {
            Difficulty.Easy -> Res.string.difficulty_easy
            Difficulty.Medium -> Res.string.difficulty_medium
            Difficulty.Hard -> Res.string.difficulty_hard
        },
    )

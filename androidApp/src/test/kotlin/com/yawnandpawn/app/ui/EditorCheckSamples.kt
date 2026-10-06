package com.yawnandpawn.app.ui

import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.editor.CheckChip
import com.yawnandpawn.app.ui.editor.CheckMode
import com.yawnandpawn.app.ui.editor.EditorForm
import com.yawnandpawn.app.ui.editor.EditorPane
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.editor.FullEditorSections

/**
 * Story 3.5 editor states as the app's ViewModel builds them: the full editor with only its Wake-up check row, offering
 * Math (the one pickable check so far). [several] uses Word Unscramble too, the look All mode gets once 3.7 adds it.
 */
object EditorCheckSamples {
    private val math = CheckChip(CheckType.Math, Difficulty.Medium, 3)

    private fun state(
        checks: List<CheckChip>,
        pane: EditorPane = EditorPane.Main,
        mode: CheckMode = CheckMode.Random,
        types: List<CheckType> = listOf(CheckType.Math),
        noCheckError: Boolean = false,
        setupType: CheckType? = null,
    ) = EditorUiState(
        form = EditorForm(checks = checks, checkMode = mode),
        pane = pane,
        setupType = setupType,
        full =
            FullEditorSections(
                checks = checks,
                checkMode = mode,
                noCheckError = noCheckError,
                rows = setOf(EditorPane.WakeCheck),
                types = types,
            ),
    )

    /** A new alarm: the main screen with the Wake-up check row ("Math"). */
    val newAlarm = state(listOf(math))

    /** The main screen after Save with no check: "None" and "Pick at least one check." under the row. */
    val mainNoCheck = state(emptyList(), noCheckError = true)

    /** The Wake-up check sub-screen with the default check. */
    val oneCheck = state(listOf(math), pane = EditorPane.WakeCheck)

    /** Two checks in All mode: "Mode" and "Move up" / "Move down" in "Your checks". */
    val several =
        state(
            listOf(CheckChip(CheckType.Math, Difficulty.Hard, 5), CheckChip(CheckType.WordUnscramble, Difficulty.Easy, 2)),
            pane = EditorPane.WakeCheck,
            mode = CheckMode.All,
            types = listOf(CheckType.Math, CheckType.WordUnscramble),
        )

    /** The Wake-up check sub-screen with nothing ticked after Save: "Pick at least one check." */
    val none = state(emptyList(), pane = EditorPane.WakeCheck, noCheckError = true)

    /** Check setup of Math · Hard · 5 (difficulty radio rows, "Problems" stepper, "Try it"). */
    val mathSetup = state(listOf(CheckChip(CheckType.Math, Difficulty.Hard, 5)), pane = EditorPane.CheckSetup, setupType = CheckType.Math)
}

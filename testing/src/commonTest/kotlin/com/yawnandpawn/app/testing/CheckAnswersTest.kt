package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.session.CheckRun
import com.yawnandpawn.app.core.session.PluginCheckValidator
import com.yawnandpawn.app.core.session.StepPointer
import com.yawnandpawn.app.core.session.StepResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CheckAnswersTest {
    private val math = CheckRun(CheckPlan(CheckMode.All, listOf(CheckPlan.DEFAULT_ENTRY)), listOf(42L))

    @Test
    fun `the right answer passes each Math item and the wrong one does not`() {
        val results =
            (0 until 3).map { item ->
                val run = math.copy(step = StepPointer(0, item))
                PluginCheckValidator.validate(run, rightAnswer(run)!!) to PluginCheckValidator.validate(run, wrongAnswer(run)!!)
            }

        assertEquals(
            listOf(StepResult.ValidNextItem, StepResult.ValidNextItem, StepResult.ValidLast).map { it to StepResult.Invalid },
            results,
        )
    }

    @Test
    fun `a placeholder entry takes the placeholder answer and a passed run has none`() {
        val placeholder = CheckRun(CheckPlan.placeholder(), listOf(1L))

        assertEquals(CheckAnswer.Placeholder, rightAnswer(placeholder))
        assertNull(wrongAnswer(placeholder))
        assertNull(rightAnswer(math.copy(step = StepPointer(1, 0))))
        assertNull(rightAnswer(math.copy(seeds = emptyList())))
    }
}

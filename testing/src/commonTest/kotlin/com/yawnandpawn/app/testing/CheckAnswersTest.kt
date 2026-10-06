package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
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
    fun `a QR entry takes its registered code, the wrong answer is another code, and an entry without a code has none`() {
        val entry = CheckEntry(CheckType.QrBarcode, Difficulty.Medium, 1, code = aRegisteredCode())
        val qr = CheckRun(CheckPlan(CheckMode.All, listOf(entry)), listOf(7L))

        assertEquals(CheckAnswer.Code(aRegisteredCode()), rightAnswer(qr))
        assertEquals(StepResult.ValidLast, PluginCheckValidator.validate(qr, rightAnswer(qr)!!))
        assertEquals(StepResult.Invalid, PluginCheckValidator.validate(qr, wrongAnswer(qr)!!))
        val noCode = qr.copy(plan = CheckPlan(CheckMode.All, listOf(entry.copy(code = null))))
        assertNull(rightAnswer(noCode))
        assertNull(wrongAnswer(noCode))
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

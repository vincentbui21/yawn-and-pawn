package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoPrintlnInCoreTest {
    private val rule = NoPrintlnInCore(Config.empty)

    @Test
    fun `println is reported`() {
        val findings =
            rule.lint(
                """
                fun ring() {
                    println("ringing")
                }
                """.trimIndent(),
            )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("println"))
    }

    @Test
    fun `print is reported`() {
        val findings = rule.lint("fun ring() { print(1) }")

        assertEquals(1, findings.size)
    }

    @Test
    fun `other calls are not reported`() {
        val findings =
            rule.lint(
                """
                interface Logger { fun info(message: String) }
                fun ring(logger: Logger) { logger.info("ringing") }
                """.trimIndent(),
            )

        assertEquals(0, findings.size)
    }

    @Test
    fun `the rule set provider exposes the rule`() {
        val ruleSet = YawnAndPawnRuleSetProvider().instance()

        assertEquals("yawn-and-pawn", ruleSet.id.value)
        assertEquals(
            listOf("NoPrintlnInCore", "NoRawColor", "NoRawCornerRadius", "NoRawSp"),
            ruleSet.rules.keys.map { it.value },
        )
    }
}

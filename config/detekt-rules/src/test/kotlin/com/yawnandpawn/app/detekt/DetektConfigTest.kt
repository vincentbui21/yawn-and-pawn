package com.yawnandpawn.app.detekt

import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Config validation skips the yawn-and-pawn key, so this test proves detekt.yml really activates every custom rule. */
class DetektConfigTest {
    private val config: Map<*, *> =
        File(checkNotNull(System.getProperty("yawnandpawn.detektConfig")) { "yawnandpawn.detektConfig not set" })
            .reader()
            .use { Yaml().load<Map<*, *>>(it) }

    @Test
    fun `detekt_yml activates the rule set and every rule the provider registers`() {
        val ruleSet = config["yawn-and-pawn"] as Map<*, *>
        assertEquals(true, ruleSet["active"], "yawn-and-pawn.active")

        val rules =
            YawnAndPawnRuleSetProvider()
                .instance()
                .rules.keys
                .map { it.value }
        assertTrue(rules.isNotEmpty())
        rules.forEach { rule ->
            val entry = ruleSet[rule] as? Map<*, *>
            assertEquals(true, entry?.get("active"), "yawn-and-pawn.$rule.active")
        }
    }
}

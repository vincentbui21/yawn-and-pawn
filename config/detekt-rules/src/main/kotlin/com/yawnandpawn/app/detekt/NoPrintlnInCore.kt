package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * `:core` must log only through the `Logger` port, never with `println` or `print`.
 * Scoped to `:core` sources by the `includes` pattern in `config/detekt/detekt.yml`.
 */
class NoPrintlnInCore(
    config: Config,
) : Rule(config, "Core code must log through the Logger port, not println or print.") {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val name = expression.calleeExpression?.text ?: return
        if (name in bannedCalls) {
            report(
                Finding(
                    Entity.from(expression),
                    "'$name' is not allowed in :core. Use the Logger port instead.",
                ),
            )
        }
    }

    private companion object {
        val bannedCalls = setOf("println", "print")
    }
}

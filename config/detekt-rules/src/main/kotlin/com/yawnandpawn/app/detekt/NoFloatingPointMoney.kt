package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Money is integer micros plus a currency (AD-8, Story 4.2): no `Double` or `Float` property or parameter whose name
 * contains `price`, `amount`, `fee` or `paid` (any case). A property without a declared type is reported when its
 * initializer is a floating-point literal. Scoped to `:core` and `:data` by the `includes` in `config/detekt/detekt.yml`.
 */
class NoFloatingPointMoney(
    config: Config,
) : Rule(config, "Money is Money(micros: Long, currency) (AD-8), never Double or Float.") {
    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        val inferredFloat = property.typeReference == null && property.initializer.isFloatLiteral()
        check(property, inferredFloat)
    }

    override fun visitParameter(parameter: KtParameter) {
        super.visitParameter(parameter)
        check(parameter, inferredFloat = false)
    }

    private fun check(
        declaration: KtCallableDeclaration,
        inferredFloat: Boolean,
    ) {
        val name = declaration.name ?: return
        if (moneyWords.none { name.contains(it, ignoreCase = true) }) return
        val type =
            declaration.typeReference
                ?.text
                ?.removeSuffix("?")
                ?.trim()
        if (inferredFloat || type in floatingTypes) {
            report(
                Finding(
                    Entity.from(declaration),
                    "'$name' holds money as a floating-point number. Use Money(micros: Long, currency) (AD-8).",
                ),
            )
        }
    }

    /** A floating-point literal, also negated or in parentheses (`-1.5`, `(2f)`). */
    private fun KtExpression?.isFloatLiteral(): Boolean =
        when (this) {
            is KtConstantExpression -> node.elementType == KtNodeTypes.FLOAT_CONSTANT
            is KtPrefixExpression -> baseExpression.isFloatLiteral()
            is KtParenthesizedExpression -> expression.isFloatLiteral()
            else -> false
        }

    private companion object {
        val moneyWords = listOf("price", "amount", "fee", "paid")
        val floatingTypes = setOf("Double", "Float", "kotlin.Double", "kotlin.Float")
    }
}

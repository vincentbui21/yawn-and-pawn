package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Money is integer micros plus a currency (AD-8, Story 4.2): no property, parameter or function whose name has the word
 * `price`, `amount`, `fee` or `paid` (camelCase or snake_case words, any case, plurals too; `feedback` is not `fee`) may
 * hold a floating-point number:
 * - a declared type that is or contains `Double`/`Float` (`Double?`, `List<Double>`, `DoubleArray`), including a
 *   function's return type;
 * - no declared type, and an initializer (or expression body) that uses a floating-point literal or calls `toDouble()` /
 *   `toFloat()` (`1.5 * n`, `cents / 100.0`, `micros.toDouble()`).
 *
 * Scoped to `:core` and `:data` by the `includes` in `config/detekt/detekt.yml`.
 */
class NoFloatingPointMoney(
    config: Config,
) : Rule(config, "Money is Money(micros: Long, currency) (AD-8), never Double or Float.") {
    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        check(property, property.initializer)
    }

    override fun visitParameter(parameter: KtParameter) {
        super.visitParameter(parameter)
        check(parameter, parameter.defaultValue)
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        check(function, function.bodyExpression?.takeIf { !function.hasBlockBody() })
    }

    private fun check(
        declaration: KtCallableDeclaration,
        value: KtExpression?,
    ) {
        val name = declaration.name ?: return
        if (words(name).none { it in moneyWords }) return
        val type = declaration.typeReference?.text
        val floating = if (type != null) floatingType.containsMatchIn(type) else value.usesFloatingPoint()
        if (floating) {
            report(
                Finding(
                    Entity.from(declaration),
                    "'$name' holds money as a floating-point number. Use Money(micros: Long, currency) (AD-8).",
                ),
            )
        }
    }

    /** A floating-point literal or a `toDouble()`/`toFloat()` call anywhere in this expression. */
    private fun KtExpression?.usesFloatingPoint(): Boolean {
        if (this == null) return false
        val literal =
            (listOf(this) + collectDescendantsOfType<KtConstantExpression>())
                .any { it is KtConstantExpression && it.node.elementType == KtNodeTypes.FLOAT_CONSTANT }
        val conversion =
            (listOf(this) + collectDescendantsOfType<KtCallExpression>())
                .any { it is KtCallExpression && it.calleeExpression?.text in floatingConversions }
        return literal || conversion
    }

    internal companion object {
        val moneyWords = setOf("price", "prices", "amount", "amounts", "fee", "fees", "paid")
        val floatingType = Regex("""\b(Double|Float|DoubleArray|FloatArray)\b""")
        val floatingConversions = setOf("toDouble", "toFloat")

        /** The lower-case words of an identifier: `basePriceUSD` → base, price, usd; `PAID_TOTAL` → paid, total. */
        fun words(name: String): List<String> =
            name
                .split('_')
                .flatMap { part -> camelWord.findAll(part).map { it.value } }
                .map { it.lowercase() }
                .filter { it.isNotEmpty() }

        private val camelWord = Regex("""[A-Z]+(?![a-z])|[A-Z]?[a-z]+|\d+""")
    }
}

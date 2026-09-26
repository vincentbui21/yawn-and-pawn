package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression

/**
 * No hand-typed colours outside `com.yawnandpawn.app.ui.theme` (AD-10, pps-design rule 1):
 * `Color(...)` with only literal arguments (`Color(0xFF112233)`, `Color(255, 0, 0)`, `Color(0.2f, 0.1f, 0.1f)`)
 * and the named Compose colours (`Color.Red`, `Color.Black`, `Color.White`, ...). `Color.Transparent` and
 * `Color.Unspecified` are allowed. Colours come from `PpsTheme.colors` / `MaterialTheme.colorScheme`.
 */
class NoRawColor(
    config: Config,
) : Rule(config, "Raw colours are not allowed outside ui.theme; use a PpsTheme colour token.") {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != "Color" || expression.isInThemePackage()) return
        val arguments = expression.argumentExpressions()
        if (arguments.isNotEmpty() && arguments.all { it.isNumberLiteral() }) report(expression)
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        if (expression.isNamedColor() && expression.isInCheckedCode()) report(expression)
    }

    private fun KtDotQualifiedExpression.isNamedColor(): Boolean {
        val receiver = receiverExpression.text
        return (receiver == "Color" || receiver.endsWith(".Color")) && selectorExpression?.text in namedColors
    }

    private fun KtDotQualifiedExpression.isInCheckedCode(): Boolean = !isInImportOrPackage() && !isInThemePackage()

    private fun report(expression: KtExpression) {
        report(
            Finding(
                Entity.from(expression),
                "Raw colour '${expression.text}'. Use a PpsTheme colour (PpsTheme.colors or MaterialTheme.colorScheme) " +
                    "generated from DESIGN.md; new colours need a DESIGN.md token first.",
            ),
        )
    }

    private companion object {
        /** Compose's named colours; Transparent and Unspecified are not colours and stay allowed. */
        val namedColors =
            setOf("Black", "DarkGray", "Gray", "LightGray", "White", "Red", "Green", "Blue", "Yellow", "Cyan", "Magenta")
    }
}

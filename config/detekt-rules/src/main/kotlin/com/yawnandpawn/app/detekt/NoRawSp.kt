package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression

/**
 * No new text sizes outside `com.yawnandpawn.app.ui.theme` (AD-10): `<number>.sp`, `<number>.em` and
 * `TextUnit(<number>, ...)` literals. Text sizes come from `PpsTheme.typography` (the DESIGN.md type ramp).
 */
class NoRawSp(
    config: Config,
) : Rule(config, "Raw text sizes (sp, em, TextUnit) are not allowed outside ui.theme; use a PpsTheme.typography style.") {
    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        if (expression.isNumberWithUnit("sp", "em") && !expression.isInThemePackage()) report(expression)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text == "TextUnit" &&
            expression.argumentExpressions().firstOrNull().isNumberLiteral() &&
            !expression.isInThemePackage()
        ) {
            report(expression)
        }
    }

    private fun report(expression: KtExpression) {
        report(
            Finding(
                Entity.from(expression),
                "Raw text size '${expression.text}'. Use a PpsTheme.typography style (clock-xl ... caption) from DESIGN.md.",
            ),
        )
    }
}

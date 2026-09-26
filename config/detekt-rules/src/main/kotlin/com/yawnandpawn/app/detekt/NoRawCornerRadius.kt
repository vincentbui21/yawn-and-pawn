package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * No ad-hoc radii outside `com.yawnandpawn.app.ui.theme` (AD-10, locked radius system 8 / 16 / 28 dp / full):
 * a corner shape or `CornerSize` with a literal argument (`RoundedCornerShape(12.dp)`, `RoundedCornerShape(12)`,
 * `RoundedCornerShape(percent = 25)`, `CornerSize(12.dp)`, `CutCornerShape(4.dp)`, ...). Use `PpsTheme.shapes`.
 */
class NoRawCornerRadius(
    config: Config,
) : Rule(config, "Raw corner radii are not allowed outside ui.theme; use PpsTheme.shapes.") {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text !in shapeCalls || expression.isInThemePackage()) return
        val raw = expression.argumentExpressions().any { it.isNumberLiteral() || it.isNumberWithUnit("dp", "px") }
        if (raw) {
            report(
                Finding(
                    Entity.from(expression),
                    "Raw corner radius '${expression.text}'. Use PpsTheme.shapes (sm 8 dp, md 16 dp, lg 28 dp, full) " +
                        "or MaterialTheme.shapes; the radius system is locked in DESIGN.md.",
                ),
            )
        }
    }

    private companion object {
        val shapeCalls =
            setOf(
                "RoundedCornerShape",
                "AbsoluteRoundedCornerShape",
                "CutCornerShape",
                "AbsoluteCutCornerShape",
                "CornerSize",
            )
    }
}

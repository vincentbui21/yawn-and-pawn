package com.yawnandpawn.app.detekt

import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/** The only package where raw design values are allowed: where `PpsTheme` is built from the generated `PpsTokens`. */
internal const val THEME_PACKAGE = "com.yawnandpawn.app.ui.theme"

/** True in [THEME_PACKAGE] and its sub-packages only (a look-alike `...feature.ui.theme` is not exempt). */
internal fun KtElement.isInThemePackage(): Boolean {
    val name = containingKtFile.packageFqName.asString()
    return name == THEME_PACKAGE || name.startsWith("$THEME_PACKAGE.")
}

/** Part of an `import` or `package` line, which name types but hold no values. */
internal fun KtElement.isInImportOrPackage(): Boolean =
    getStrictParentOfType<KtImportDirective>() != null || getStrictParentOfType<KtPackageDirective>() != null

/** A number literal, optionally negated or parenthesised: `12`, `0.5f`, `-2`, `(3)`. */
internal fun KtExpression?.isNumberLiteral(): Boolean {
    var expression = this
    while (expression is KtParenthesizedExpression || expression is KtPrefixExpression) {
        expression = (expression as? KtParenthesizedExpression)?.expression ?: (expression as? KtPrefixExpression)?.baseExpression
    }
    return expression is KtConstantExpression
}

/** `12.dp`, `14.sp`, `1.5f.sp`, `(-2).sp`: a number literal with one of [units] as the selector. */
internal fun KtExpression.isNumberWithUnit(vararg units: String): Boolean {
    val qualified = this as? KtDotQualifiedExpression
    return qualified != null && qualified.selectorExpression?.text in units && qualified.receiverExpression.isNumberLiteral()
}

/** The call's argument expressions (named or positional). */
internal fun KtCallExpression.argumentExpressions(): List<KtExpression> = valueArguments.mapNotNull { it.getArgumentExpression() }

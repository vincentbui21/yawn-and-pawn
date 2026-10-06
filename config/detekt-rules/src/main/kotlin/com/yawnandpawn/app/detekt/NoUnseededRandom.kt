package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * The packages where a puzzle must follow from its seed (AD-9, Story 3.1): the check plugins and the session that stores
 * their seeds. Seeds come only from `SeedDeriver`.
 */
internal val SEEDED_PACKAGES = listOf("com.yawnandpawn.app.core.checks", "com.yawnandpawn.app.core.session")

/**
 * No unseeded randomness in [SEEDED_PACKAGES], so a restored session regenerates the same puzzle. Reported:
 * - any member of the `Random` companion (`Random.nextInt()`, `Random.Default`, `kotlin.random.Random.nextLong()`);
 * - `Random()` and `SecureRandom()` without a seed, `ThreadLocalRandom.current()`;
 * - `random()`, `randomOrNull()`, `shuffled()` and `shuffle()` without a generator argument (they use `Random.Default`;
 *   this also covers `Math.random()` and `Uuid.random()`);
 * - imports of `Random`'s companion members, `java.util.Random`, `java.security.SecureRandom`,
 *   `java.util.concurrent.ThreadLocalRandom`, and `kotlin.random.Random` under another name.
 *
 * Allowed: a generator made from a seed (`Random(seed)`, the project's `SeededRandom`) and calls that are given one.
 * Scoped to `:core` sources by `includes` in `config/detekt/detekt.yml`, and to [SEEDED_PACKAGES] inside the rule.
 */
class NoUnseededRandom(
    config: Config,
) : Rule(config, "Check puzzles follow only from SeedDeriver seeds: no unseeded random in core.checks or core.session.") {
    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        if (expression.isInImportOrPackage()) return
        val receiver = expression.receiverExpression.text.removeSuffix(".Companion")
        if (receiver.isName("Random") || receiver.isName("ThreadLocalRandom")) reportIfSeeded(expression, expression.text)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val name = expression.calleeExpression?.text ?: return
        val noArguments = expression.valueArguments.isEmpty() && expression.lambdaArguments.isEmpty()
        if (noArguments && (name in unseededCalls || name in unseededConstructors)) reportIfSeeded(expression, "$name()")
    }

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val path = importDirective.importedFqName?.asString() ?: return
        val banned =
            path.startsWith("kotlin.random.Random.") ||
                path in bannedImports ||
                (path == "kotlin.random.Random" && importDirective.aliasName != null)
        if (banned) reportIfSeeded(importDirective, importDirective.text)
    }

    private fun String.isName(simpleName: String): Boolean = this == simpleName || endsWith(".$simpleName")

    private fun reportIfSeeded(
        element: KtElement,
        what: String,
    ) {
        val name = element.containingKtFile.packageFqName.asString()
        if (SEEDED_PACKAGES.none { name == it || name.startsWith("$it.") }) return
        report(
            Finding(
                Entity.from(element),
                "Unseeded random '$what' in $name. Derive the seed with SeedDeriver and draw from SeededRandom(seed), " +
                    "so a restored session shows the same puzzle.",
            ),
        )
    }

    private companion object {
        /** Calls that draw from `Random.Default` (or a platform source) when no generator is passed. */
        val unseededCalls = setOf("random", "randomOrNull", "shuffled", "shuffle")

        /** Generators that seed themselves when built without arguments. */
        val unseededConstructors = setOf("Random", "SecureRandom")

        val bannedImports = setOf("java.util.Random", "java.security.SecureRandom", "java.util.concurrent.ThreadLocalRandom")
    }
}

package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Alarms are scheduled only with `AlarmManager.setAlarmClock()` behind the `AlarmScheduler` port (AD-4), never with
 * an inexact, windowed or repeating alarm, and there is no inexact fallback. Reported in files that import or refer to
 * `android.app.AlarmManager` or `androidx.core.app.AlarmManagerCompat`: calls (and callable references) named `set`,
 * `setExact`, `setExactAndAllowWhileIdle`, `setAndAllowWhileIdle`, `setRepeating`, `setInexactRepeating` and
 * `setWindow`. Without type resolution the rule cannot tell an `AlarmManager` receiver from any other, so in such a
 * file every call with one of these names counts.
 */
class NoInexactAlarm(
    config: Config,
) : Rule(config, "Schedule alarms only through the AlarmScheduler port, which uses AlarmManager.setAlarmClock(); no inexact alarm APIs.") {
    private var checkedFile: KtFile? = null
    private var fileUsesAlarmManager = false

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val name = expression.calleeExpression?.text ?: return
        if (name in bannedCalls && expression.containingKtFile.usesAlarmManager()) report(expression, name)
    }

    override fun visitCallableReferenceExpression(expression: KtCallableReferenceExpression) {
        super.visitCallableReferenceExpression(expression)
        val name = expression.callableReference.getReferencedName()
        if (name in bannedCalls && expression.containingKtFile.usesAlarmManager()) report(expression, name)
    }

    private fun report(
        element: KtElement,
        name: String,
    ) {
        report(
            Finding(
                Entity.from(element),
                "Inexact alarm API '$name'. Schedule through the AlarmScheduler port; its adapter uses only " +
                    "AlarmManager.setAlarmClock() and never falls back to an inexact alarm (AD-4).",
            ),
        )
    }

    /**
     * True when the file imports `android.app.AlarmManager` or `androidx.core.app.AlarmManagerCompat` (also
     * `android.app.*` or an alias) or names either.
     */
    private fun KtFile.usesAlarmManager(): Boolean {
        if (checkedFile !== this) {
            checkedFile = this
            fileUsesAlarmManager =
                importDirectives.any { it.importsAlarmManager() } ||
                collectDescendantsOfType<KtNameReferenceExpression> { it.getReferencedName() in alarmManagerNames }.isNotEmpty()
        }
        return fileUsesAlarmManager
    }

    private fun KtImportDirective.importsAlarmManager(): Boolean {
        val path = importedFqName?.asString()
        return path == "android.app.AlarmManager" ||
            path == "androidx.core.app.AlarmManagerCompat" ||
            (isAllUnder && path == "android.app")
    }

    private companion object {
        // AlarmManagerCompat (androidx.core) wraps the same inexact calls: setExact, setAndAllowWhileIdle, ...
        val alarmManagerNames = setOf("AlarmManager", "AlarmManagerCompat")

        val bannedCalls =
            setOf(
                "set",
                "setExact",
                "setExactAndAllowWhileIdle",
                "setAndAllowWhileIdle",
                "setRepeating",
                "setInexactRepeating",
                "setWindow",
            )
    }
}

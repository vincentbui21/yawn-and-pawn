package com.yawnandpawn.app.detekt

import com.intellij.psi.PsiElement
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgumentName

/**
 * Every app file lives in device-protected storage, so the alarm rings before the first unlock after a reboot
 * (AD-15, Story 2.3). Reports storage on a context that is not the device-protected one:
 * - calls `getDatabasePath`, `getSharedPreferences`, `getFilesDir`, `getCacheDir`, `getDataDir`, `getNoBackupFilesDir`,
 *   `getCodeCacheDir`, `getDir`, `openFileOutput`, `openFileInput`, `deleteDatabase`, `databaseList`;
 * - property reads `filesDir`, `cacheDir`, `dataDir`, `noBackupFilesDir`, `codeCacheDir`;
 * - `Room.databaseBuilder(context, …)` whose context argument is not the device-protected one;
 * - always, whatever the receiver: `preferencesDataStore(...)`, `dataStoreFile(...)` and `preferencesDataStoreFile(...)`,
 *   whose file is in `applicationContext.filesDir` (credential-protected) even when called on the device context.
 *
 * Allowed: the receiver (or Room's context argument), with parentheses and `!!` removed, is a
 * `createDeviceProtectedStorageContext(…)` call (also `ContextCompat.createDeviceProtectedStorageContext(ctx)`), or a
 * name containing `device` or `Device` (for example `deviceContext` or `this.deviceContext`). Without type resolution
 * the rule cannot follow a context through other names, so keep the device-protected context in such a name. An
 * unqualified `filesDir` that names a local value, a parameter or a property of the enclosing class, or a named
 * argument, is not a context read. A file in package `…android.media` (or below it) is exempt: credential-protected
 * media (Epic 7) live there.
 */
class CredentialStorageAccess(
    config: Config,
) : Rule(config, "App storage only through createDeviceProtectedStorageContext(), so it opens before the first unlock (AD-15).") {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val name = expression.calleeExpression?.text ?: return
        when {
            name in alwaysCredential -> {
                reportIfNotExempt(expression, name)
            }

            name == ROOM_BUILDER -> {
                val context = contextArgument(expression) ?: return
                if (!deviceProtected(context)) reportIfNotExempt(expression, name)
            }

            name in storageCalls && !deviceProtected(receiverOf(expression)) -> {
                reportIfNotExempt(expression, name)
            }
        }
    }

    override fun visitReferenceExpression(expression: KtReferenceExpression) {
        super.visitReferenceExpression(expression)
        val reference = expression as? KtNameReferenceExpression ?: return
        val name = reference.getReferencedName()
        if (isContextRead(reference, name) && !deviceProtected(receiverOf(reference))) reportIfNotExempt(reference, name)
    }

    /**
     * [reference] reads a storage property of a context. Not a call's callee (visitCallExpression handles those), not a
     * named argument, and when unqualified not a name the code declares itself.
     */
    private fun isContextRead(
        reference: KtNameReferenceExpression,
        name: String,
    ): Boolean =
        name in storageProperties &&
            reference.parent !is KtCallExpression &&
            reference.parent !is KtValueArgumentName &&
            (receiverOf(reference) != null || !declaredInScope(reference, name))

    /** The receiver of [element] when it is the selector of `receiver.element` (or `?.`); null when unqualified. */
    private fun receiverOf(element: KtElement): KtExpression? =
        when (val parent = element.parent) {
            is KtDotQualifiedExpression -> parent.receiverExpression.takeIf { parent.selectorExpression == element }
            is KtSafeQualifiedExpression -> parent.receiverExpression.takeIf { parent.selectorExpression == element }
            else -> null
        }

    /** Room's context argument: named `context`, or the first positional one unless it is the KMP builder's name string. */
    private fun contextArgument(call: KtCallExpression): KtExpression? {
        val arguments = call.valueArguments
        val argument =
            arguments.firstOrNull { it.getArgumentName()?.asName?.asString() == "context" }
                ?: arguments.firstOrNull()?.takeUnless { it.isNamed() }
        return argument?.getArgumentExpression()?.takeUnless { it is KtStringTemplateExpression }
    }

    private fun deviceProtected(receiver: KtExpression?): Boolean =
        when (val bare = unwrap(receiver)) {
            is KtNameReferenceExpression -> bare.getReferencedName().contains("device", ignoreCase = true)
            is KtCallExpression -> bare.calleeExpression?.text == DEVICE_CONTEXT_CALL
            is KtQualifiedExpression -> deviceProtected(bare.selectorExpression)
            else -> false
        }

    /** [expression] without parentheses and `!!`. */
    private fun unwrap(expression: KtExpression?): KtExpression? {
        var current = expression?.let(KtPsiUtil::safeDeparenthesize)
        while (current is KtPostfixExpression && current.operationToken == KtTokens.EXCLEXCL) {
            current = current.baseExpression?.let(KtPsiUtil::safeDeparenthesize)
        }
        return current
    }

    /** An unqualified [name] declared by an enclosing function, lambda, loop, block or class: not the Context's property. */
    private fun declaredInScope(
        reference: KtElement,
        name: String,
    ): Boolean =
        generateSequence<PsiElement>(reference.parent) { it.parent }
            .takeWhile { it !is KtFile }
            .any { scope ->
                when (scope) {
                    is KtFunction -> scope.valueParameters.any { it.name == name }
                    is KtForExpression -> scope.loopParameter?.name == name
                    is KtClass -> (scope.primaryConstructorParameters + scope.getProperties()).any { it.name == name }
                    is KtBlockExpression -> scope.statements.any { it.textOffset < reference.textOffset && it.declares(name) }
                    else -> false
                }
            }

    private fun KtExpression.declares(name: String): Boolean =
        (this is KtProperty && this.name == name) || (this is KtDestructuringDeclaration && entries.any { it.name == name })

    private fun reportIfNotExempt(
        element: KtElement,
        name: String,
    ) {
        val packageName = element.containingKtFile.packageFqName.asString()
        if (packageName.endsWith(MEDIA_PACKAGE) || packageName.contains("$MEDIA_PACKAGE.")) return
        report(
            Finding(
                Entity.from(element),
                "Credential-protected storage '$name'. Use createDeviceProtectedStorageContext() (or a deviceContext name for " +
                    "it): the alarm must ring before the first unlock (AD-15). Only android.media may use normal storage.",
            ),
        )
    }

    private companion object {
        const val DEVICE_CONTEXT_CALL = "createDeviceProtectedStorageContext"
        const val ROOM_BUILDER = "databaseBuilder"
        const val MEDIA_PACKAGE = ".android.media"

        /** Always `applicationContext.filesDir`, so credential-protected even on the device context. */
        val alwaysCredential = setOf("preferencesDataStore", "dataStoreFile", "preferencesDataStoreFile")
        val storageCalls =
            setOf(
                "getDatabasePath",
                "getSharedPreferences",
                "getFilesDir",
                "getCacheDir",
                "getDataDir",
                "getNoBackupFilesDir",
                "getCodeCacheDir",
                "getDir",
                "openFileOutput",
                "openFileInput",
                "deleteDatabase",
                "databaseList",
            )
        val storageProperties = setOf("filesDir", "cacheDir", "dataDir", "noBackupFilesDir", "codeCacheDir")
    }
}

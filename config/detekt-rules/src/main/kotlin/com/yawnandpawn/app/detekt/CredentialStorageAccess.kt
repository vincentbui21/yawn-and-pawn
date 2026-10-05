package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression

/**
 * Every app file lives in device-protected storage, so the alarm rings before the first unlock after a reboot
 * (AD-15, Story 2.3). Reports storage on a context that is not the device-protected one:
 * - calls `getDatabasePath`, `getSharedPreferences`, `dataStoreFile`, `getFilesDir`, `getCacheDir`, `getDataDir`;
 * - property reads `filesDir`, `cacheDir`, `dataDir`;
 * - `preferencesDataStore(...)`, whose file is always in credential-protected storage.
 *
 * Allowed: the receiver is a `createDeviceProtectedStorageContext()` call, or a name containing `device` or `Device`
 * (for example `deviceContext`). Without type resolution the rule cannot follow a context through other names, so keep
 * the device-protected context in such a name. A file in a package `…android.media` is exempt: credential-protected
 * media (Epic 7) live there.
 */
class CredentialStorageAccess(
    config: Config,
) : Rule(config, "App storage only through createDeviceProtectedStorageContext(), so it opens before the first unlock (AD-15).") {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val name = expression.calleeExpression?.text ?: return
        when {
            name == ALWAYS_CREDENTIAL -> reportIfNotExempt(expression, name)
            name in storageCalls && !deviceProtected(receiverOf(expression)) -> reportIfNotExempt(expression, name)
        }
    }

    override fun visitReferenceExpression(expression: KtReferenceExpression) {
        super.visitReferenceExpression(expression)
        val reference = expression as? KtNameReferenceExpression ?: return
        val name = reference.getReferencedName()
        // A call's callee is handled by visitCallExpression; here only property reads.
        if (name !in storageProperties || reference.parent is KtCallExpression) return
        if (!deviceProtected(receiverOf(reference))) reportIfNotExempt(reference, name)
    }

    /** The receiver of [element] when it is the selector of `receiver.element` (or `?.`); null when unqualified. */
    private fun receiverOf(element: KtElement): KtExpression? =
        when (val parent = element.parent) {
            is KtDotQualifiedExpression -> parent.receiverExpression.takeIf { parent.selectorExpression == element }
            is KtSafeQualifiedExpression -> parent.receiverExpression.takeIf { parent.selectorExpression == element }
            else -> null
        }

    private fun deviceProtected(receiver: KtExpression?): Boolean {
        if (receiver == null) return false
        val text = receiver.text
        return text.endsWith("$DEVICE_CONTEXT_CALL()") ||
            (receiver is KtNameReferenceExpression && text.contains("device", ignoreCase = true))
    }

    private fun reportIfNotExempt(
        element: KtElement,
        name: String,
    ) {
        if (element.containingKtFile.packageFqName
                .asString()
                .contains(MEDIA_PACKAGE)
        ) {
            return
        }
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
        const val ALWAYS_CREDENTIAL = "preferencesDataStore"
        const val MEDIA_PACKAGE = ".android.media"

        val storageCalls = setOf("getDatabasePath", "getSharedPreferences", "dataStoreFile", "getFilesDir", "getCacheDir", "getDataDir")
        val storageProperties = setOf("filesDir", "cacheDir", "dataDir")
    }
}

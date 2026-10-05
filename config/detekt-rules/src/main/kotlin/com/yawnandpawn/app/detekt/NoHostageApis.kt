package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * No device hostage (Story 2.11, FR-SES-9, NFR-13, Play policy): the alarm never blocks Home, Recents, calls, the
 * emergency dialer or other apps. One rule for every banned API, so later stories (2.7 calls) rely on it:
 * - **Lock task, launcher, overlays, keyguard:** `startLockTask`, `setLockTaskPackages`, `moveTaskToFront`,
 *   `newKeyguardLock`, `addView` on a window manager, `TYPE_APPLICATION_OVERLAY`, `TYPE_SYSTEM_ALERT`,
 *   `DevicePolicyManager`, `AccessibilityService`.
 * - **Telephony:** `TelephonyManager`, `PhoneStateListener`, `TelephonyCallback`, `registerTelephonyCallback`; calls are
 *   detected through the audio mode only.
 * - **Volume keys and audio routing** (Story 2.8): `MediaSession(Compat)`, `VolumeProvider(Compat)`,
 *   `setPreferredDevice`, `setSpeakerphoneOn` / `isSpeakerphoneOn`, `setCommunicationDevice`,
 *   `registerMediaButtonEventReceiver`.
 * - **Activity starts from the background:** `startActivity` / `startActivities` inside a `Service` or a
 *   `BroadcastReceiver` subclass, or anywhere in `com.yawnandpawn.app.android.receiver` and
 *   `com.yawnandpawn.app.android.wake` except inside `WakeActivity`. Starts from the screen in front live in
 *   `com.yawnandpawn.app.android.screen`.
 * - **Key overrides:** `onKeyDown`, `onKeyUp`, `onKeyLongPress` or `dispatchKeyEvent` overrides that name
 *   `KEYCODE_HOME`, `KEYCODE_APP_SWITCH` or `KEYCODE_POWER`.
 *
 * Without type resolution the rule matches names; `addView` counts only when its receiver names a window manager.
 */
class NoHostageApis(
    config: Config,
) : Rule(config, "No device hostage: never block Home, Recents, calls, the emergency dialer or other apps (NFR-13).") {
    override fun visitUserType(type: KtUserType) {
        super.visitUserType(type)
        val name = type.referencedName ?: return
        if (name in bannedNames) report(type, name)
    }

    override fun visitReferenceExpression(expression: KtReferenceExpression) {
        super.visitReferenceExpression(expression)
        // Callees are checked as calls, type names as types; an import alone is not a use.
        if (expression !is KtNameReferenceExpression || expression.parent is KtCallExpression || expression.parent is KtUserType) return
        if (expression.getStrictParentOfType<KtImportDirective>() != null ||
            expression.getStrictParentOfType<KtPackageDirective>() != null
        ) {
            return
        }
        val name = expression.getReferencedName()
        if (name in bannedNames || name in bannedProperties) report(expression, name)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val name = expression.calleeExpression?.text ?: return
        when {
            name in bannedCalls || name in bannedNames -> report(expression, name)
            name == "addView" && onWindowManager(expression) -> report(expression, "WindowManager.addView")
            name in activityStarts && startsFromBackground(expression) -> report(expression, "$name from the background")
        }
    }

    override fun visitCallableReferenceExpression(expression: KtCallableReferenceExpression) {
        super.visitCallableReferenceExpression(expression)
        val name = expression.callableReference.getReferencedName()
        if (name in bannedCalls) report(expression, name)
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (function.name !in keyHandlers || !function.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return
        val body = function.bodyExpression?.text ?: return
        systemKeys.filter { body.contains(it) }.forEach { key -> report(function, "${function.name} handling $key") }
    }

    /**
     * True when the receiver of [call] names a window manager, or is a parameter or property of this file declared as
     * one (`wm: WindowManager`).
     */
    private fun onWindowManager(call: KtCallExpression): Boolean {
        val receiver = receiverText(call)
        return receiver.contains(WINDOW_MANAGER) || (receiver.isNotEmpty() && declaredAsWindowManager(call, receiver))
    }

    private fun declaredAsWindowManager(
        call: KtCallExpression,
        name: String,
    ): Boolean =
        call.containingKtFile
            .collectDescendantsOfType<KtCallableDeclaration> { declaration ->
                declaration.name == name &&
                    declaration.typeReference
                        ?.text
                        .orEmpty()
                        .contains(WINDOW_MANAGER)
            }.isNotEmpty()

    private fun receiverText(call: KtCallExpression): String =
        (call.parent as? KtDotQualifiedExpression)
            ?.takeIf { it.selectorExpression == call }
            ?.receiverExpression
            ?.text
            .orEmpty()

    /** True inside a service or receiver class, or in the receiver and wake packages outside `WakeActivity`. */
    private fun startsFromBackground(call: KtCallExpression): Boolean {
        val classes = generateSequence(call.getStrictParentOfType<KtClassOrObject>()) { it.containingClassOrObject }.toList()
        if (classes.any { it.extendsBackgroundComponent() }) return true
        val packageName = call.containingKtFile.packageFqName.asString()
        val inBackgroundPackage = backgroundPackages.any { packageName == it || packageName.startsWith("$it.") }
        return inBackgroundPackage && classes.lastOrNull()?.name != WAKE_ACTIVITY
    }

    private fun KtClassOrObject.extendsBackgroundComponent(): Boolean =
        superTypeListEntries.any { entry ->
            val type =
                entry.typeReference
                    ?.text
                    ?.substringBefore('<')
                    ?.substringAfterLast('.')
                    .orEmpty()
            type.endsWith("Service") || type.endsWith("BroadcastReceiver") || type == "BroadcastReceiver"
        }

    private fun report(
        element: KtElement,
        name: String,
    ) {
        report(
            Finding(
                Entity.from(element),
                "'$name' could hold the phone hostage (NFR-13, Play policy): Home, Recents, calls, the emergency dialer and " +
                    "other apps must always work; the wake screen opens only from its notification or the screen in front.",
            ),
        )
    }

    private companion object {
        const val WAKE_ACTIVITY = "WakeActivity"
        const val WINDOW_MANAGER = "WindowManager"

        val bannedNames =
            setOf(
                "TYPE_APPLICATION_OVERLAY",
                "TYPE_SYSTEM_ALERT",
                "DevicePolicyManager",
                "AccessibilityService",
                "TelephonyManager",
                "PhoneStateListener",
                "TelephonyCallback",
                "MediaSession",
                "MediaSessionCompat",
                "VolumeProvider",
                "VolumeProviderCompat",
            )

        val bannedCalls =
            setOf(
                "startLockTask",
                "setLockTaskPackages",
                "moveTaskToFront",
                "newKeyguardLock",
                "registerTelephonyCallback",
                "setPreferredDevice",
                "setSpeakerphoneOn",
                "setCommunicationDevice",
                "registerMediaButtonEventReceiver",
            )

        val bannedProperties = setOf("isSpeakerphoneOn")

        val activityStarts = setOf("startActivity", "startActivities")

        val backgroundPackages = listOf("com.yawnandpawn.app.android.receiver", "com.yawnandpawn.app.android.wake")

        val keyHandlers = setOf("onKeyDown", "onKeyUp", "onKeyLongPress", "dispatchKeyEvent")

        val systemKeys = listOf("KEYCODE_HOME", "KEYCODE_APP_SWITCH", "KEYCODE_POWER")
    }
}

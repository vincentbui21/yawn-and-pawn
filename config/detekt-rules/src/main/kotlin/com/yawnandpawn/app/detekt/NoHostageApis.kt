package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.parents

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
 *   `registerMediaButtonEventReceiver`, `startBluetoothSco`, `setBluetoothScoOn` / `isBluetoothScoOn`, and switching
 *   the audio mode to a call (`setMode(...)` or `mode = ...` with `MODE_IN_COMMUNICATION` or `MODE_IN_CALL`).
 * - **Activity starts from the background:** `startActivity`, `startActivities`, `startActivityIfNeeded`,
 *   `startIntentSender`, `PendingIntent.send` (calls or `::` references) inside a `Service`, `BroadcastReceiver`,
 *   `Worker` / `CoroutineWorker`, `ContentProvider` or `Application` subclass (any enclosing class, object literals
 *   included), or anywhere in the receivers' package `com.yawnandpawn.app.android` and in
 *   `com.yawnandpawn.app.android.wake` except inside `WakeActivity`. The two starts from the screen in front
 *   (forwarding while resumed, "Back to alarm") live in `com.yawnandpawn.app.android.screen`.
 * - **System keys:** `KEYCODE_HOME`, `KEYCODE_APP_SWITCH`, `KEYCODE_POWER` and Compose's `Key.Home`, `Key.AppSwitch`,
 *   `Key.Power`, anywhere but an import.
 *
 * An import alias of any banned name is reported, since it hides every use below. Without type resolution the rule
 * matches names; `addView` and `send` count only when their receiver (explicit, safe-call or the implicit one of
 * `with(x)`, `x.apply`, `x.run`, `it` in `x.let` / `x.also`) names a window manager / pending intent or is declared
 * as one in the same file (by type, initializer or delegate).
 */
class NoHostageApis(
    config: Config,
) : Rule(config, "No device hostage: never block Home, Recents, calls, the emergency dialer or other apps (NFR-13).") {
    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        if (importDirective.aliasName == null) return
        val fqName = importDirective.importedFqName ?: return
        val name = fqName.shortName().asString()
        val composeKey = name in composeSystemKeys && ".Key." in fqName.asString()
        if (name in aliasBanned || composeKey) report(importDirective, name)
    }

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
        when {
            name in bannedNames || name in bannedProperties || name in systemKeys -> report(expression, name)
            name in composeSystemKeys && expression.isComposeKey() -> report(expression, "Key.$name")
        }
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val name = expression.calleeExpression?.text ?: return
        val callMode = name == "setMode" && expression.valueArguments.any { it.text.mentionsCallMode() }
        when {
            name in bannedCalls || name in bannedNames || name in bannedProperties || callMode -> {
                report(expression, name)
            }

            name == "addView" && expression.receiverIs(windowManagerMarkers) -> {
                report(expression, "WindowManager.addView")
            }

            name in activityStarts && expression.startsFromBackground() -> {
                report(expression, "$name from the background")
            }

            name == "send" && expression.receiverIs(pendingIntentMarkers) && expression.startsFromBackground() -> {
                report(expression, "PendingIntent.send from the background")
            }
        }
    }

    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        super.visitBinaryExpression(expression)
        if (expression.operationToken != KtTokens.EQ) return
        val target = expression.left?.text?.substringAfterLast('.')
        if (target == "mode" &&
            expression.right
                ?.text
                .orEmpty()
                .mentionsCallMode()
        ) {
            report(expression, "mode")
        }
    }

    override fun visitCallableReferenceExpression(expression: KtCallableReferenceExpression) {
        super.visitCallableReferenceExpression(expression)
        val name = expression.callableReference.getReferencedName()
        when {
            name in bannedCalls -> report(expression, name)
            name in activityStarts && expression.startsFromBackground() -> report(expression, "::$name from the background")
        }
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
}

private fun String.mentionsCallMode(): Boolean = callModes.any { contains(it) }

/** `Key.Home` (or a qualified `...input.key.Key.Home`), not some other `Home`. */
private fun KtNameReferenceExpression.isComposeKey(): Boolean =
    (parent as? KtQualifiedExpression)
        ?.takeIf { it.selectorExpression == this }
        ?.receiverExpression
        ?.text
        ?.removeSuffix(".Companion")
        ?.substringAfterLast('.') == "Key"

/**
 * True when the receiver of this call mentions one of [markers], or is a property or parameter of this file declared
 * with one (type, initializer or delegate).
 */
private fun KtCallExpression.receiverIs(markers: List<String>): Boolean {
    val receiver = receiverText()
    val name =
        receiver
            .removePrefix("this.")
            .removeSuffix("!!")
            .trim()
    val named = markers.any { receiver.contains(it, ignoreCase = true) }
    return receiver.isNotEmpty() &&
        (
            named ||
                containingKtFile
                    .collectDescendantsOfType<KtCallableDeclaration> { it.name == name && (it is KtProperty || it is KtParameter) }
                    .any { declaration -> declaration.declaredTexts().any { text -> markers.any { text.contains(it, ignoreCase = true) } } }
        )
}

private fun KtCallableDeclaration.declaredTexts(): List<String> =
    listOfNotNull(
        typeReference?.text,
        (this as? KtProperty)?.initializer?.text,
        (this as? KtProperty)?.delegateExpression?.text,
        (this as? KtParameter)?.defaultValue?.text,
    )

/** The explicit receiver (`x.call()` or `x?.call()`), else the implicit one of an enclosing scope function. */
private fun KtCallExpression.receiverText(): String {
    val explicit = (parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == this }?.receiverExpression?.text
    return when (explicit) {
        null -> scopeReceiver(thisScopes).orEmpty()
        "it" -> scopeReceiver(itScopes) ?: explicit
        else -> explicit
    }
}

/** The receiver given to the innermost enclosing lambda of [scopes]: `with(x) { }` gives `x`, `x.apply { }` gives `x`. */
private fun KtElement.scopeReceiver(scopes: Set<String>): String? =
    parents
        .filterIsInstance<KtLambdaExpression>()
        .mapNotNull { lambda ->
            val argument = lambda.parent as? KtValueArgument ?: return@mapNotNull null
            val scopeCall = argument.getStrictParentOfType<KtCallExpression>() ?: return@mapNotNull null
            val scope = scopeCall.calleeExpression?.text
            when {
                scope !in scopes -> {
                    null
                }

                scope == "with" -> {
                    scopeCall.valueArguments
                        .firstOrNull()
                        ?.takeIf { it != argument }
                        ?.text
                }

                else -> {
                    (scopeCall.parent as? KtQualifiedExpression)
                        ?.takeIf { it.selectorExpression == scopeCall }
                        ?.receiverExpression
                        ?.text
                }
            }
        }.firstOrNull()

/** True inside any (enclosing) background component class, or in the receivers' and wake packages outside `WakeActivity`. */
private fun KtElement.startsFromBackground(): Boolean {
    val classes = parents.filterIsInstance<KtClassOrObject>().toList()
    val packageName = containingKtFile.packageFqName.asString()
    val inBackgroundPackage =
        packageName == RECEIVER_PACKAGE || packageName == WAKE_PACKAGE || packageName.startsWith("$WAKE_PACKAGE.")
    return classes.any { it.extendsBackgroundComponent() } ||
        (inBackgroundPackage && classes.lastOrNull()?.name != WAKE_ACTIVITY)
}

private fun KtClassOrObject.extendsBackgroundComponent(): Boolean =
    superTypeListEntries.any { entry ->
        val type =
            entry.typeReference
                ?.text
                ?.substringBefore('<')
                ?.substringAfterLast('.')
                .orEmpty()
        backgroundComponents.any { type.endsWith(it) }
    }

private const val WAKE_ACTIVITY = "WakeActivity"

/** The broadcast receivers (`AlarmFiredReceiver`, `SessionSlotReceiver`, `SystemEventsReceiver`) live here. */
private const val RECEIVER_PACKAGE = "com.yawnandpawn.app.android"
private const val WAKE_PACKAGE = "com.yawnandpawn.app.android.wake"

private val bannedNames =
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

private val bannedCalls =
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
        "startBluetoothSco",
        "setBluetoothScoOn",
    )

private val bannedProperties = setOf("isSpeakerphoneOn", "isBluetoothScoOn")

private val callModes = listOf("MODE_IN_COMMUNICATION", "MODE_IN_CALL")

private val systemKeys = setOf("KEYCODE_HOME", "KEYCODE_APP_SWITCH", "KEYCODE_POWER")

private val composeSystemKeys = setOf("Home", "AppSwitch", "Power")

private val aliasBanned = bannedNames + bannedCalls + bannedProperties + systemKeys

private val activityStarts = setOf("startActivity", "startActivities", "startActivityIfNeeded", "startIntentSender")

private val backgroundComponents = listOf("Service", "BroadcastReceiver", "Worker", "ContentProvider", "Application")

private val windowManagerMarkers = listOf("WindowManager", "WINDOW_SERVICE")

private val pendingIntentMarkers = listOf("PendingIntent")

private val thisScopes = setOf("with", "apply", "run")

private val itScopes = setOf("let", "also")

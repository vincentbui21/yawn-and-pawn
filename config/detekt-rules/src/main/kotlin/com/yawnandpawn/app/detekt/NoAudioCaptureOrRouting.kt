package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtUserType

/**
 * The alarm never takes the volume keys away from the phone and never reroutes audio (Story 2.8, FR-SES-6, NFR-13). The
 * wake screen swallows the volume keys only while it is in front (`VolumeKeyGate`); a `MediaSession` or a
 * `VolumeProvider` would capture them everywhere. Routing stays with the system for `USAGE_ALARM` (wired or Bluetooth
 * headphones included). Reported:
 * - the types `MediaSession`, `MediaSessionCompat`, `VolumeProvider` and `VolumeProviderCompat`, also through an import
 *   alias, a constructor reference (`::MediaSession`) or a class literal (`MediaSession::class`);
 * - the calls (and references) `setPreferredDevice`, `setSpeakerphoneOn`, `setCommunicationDevice`,
 *   `registerMediaButtonEventReceiver`, `startBluetoothSco` and `setBluetoothScoOn`, and the properties
 *   `isSpeakerphoneOn` and `isBluetoothScoOn`;
 * - switching the audio mode to a call: `setMode(...)` or `mode = ...` with `MODE_IN_COMMUNICATION` or `MODE_IN_CALL`.
 *
 * Story 2.11's no-hostage rule extends this list.
 */
class NoAudioCaptureOrRouting(
    config: Config,
) : Rule(config, "No media session, volume provider or audio rerouting: the volume keys and audio routing stay the system's.") {
    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        // An alias hides the banned name from every use below, so the aliased import itself is reported.
        if (importDirective.aliasName == null) return
        val name = importDirective.importedFqName?.shortName()?.asString() ?: return
        if (name in bannedTypes) report(importDirective, name)
    }

    override fun visitUserType(type: KtUserType) {
        super.visitUserType(type)
        val name = type.referencedName ?: return
        if (name in bannedTypes) report(type, name)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val name = expression.calleeExpression?.text ?: return
        val callMode = name == "setMode" && expression.valueArguments.any { it.text.mentionsCallMode() }
        if (name in bannedTypes || name in bannedCalls || callMode) report(expression, name)
    }

    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        super.visitBinaryExpression(expression)
        if (expression.operationToken != KtTokens.EQ) return
        val target = expression.left?.text?.substringAfterLast('.')
        val value = expression.right?.text.orEmpty()
        if (target == "mode" && value.mentionsCallMode()) report(expression, "mode")
    }

    override fun visitCallableReferenceExpression(expression: KtCallableReferenceExpression) {
        super.visitCallableReferenceExpression(expression)
        val name = expression.callableReference.getReferencedName()
        if (name in bannedCalls || name in bannedTypes) report(expression, name)
    }

    override fun visitClassLiteralExpression(expression: KtClassLiteralExpression) {
        super.visitClassLiteralExpression(expression)
        val name = expression.receiverExpression?.text?.substringAfterLast('.') ?: return
        if (name in bannedTypes) report(expression, name)
    }

    override fun visitReferenceExpression(expression: KtReferenceExpression) {
        super.visitReferenceExpression(expression)
        if (expression is KtNameReferenceExpression && expression.getReferencedName() in bannedProperties) {
            report(expression, expression.getReferencedName())
        }
    }

    private fun String.mentionsCallMode(): Boolean = callModes.any { contains(it) }

    private fun report(
        element: KtElement,
        name: String,
    ) {
        report(
            Finding(
                Entity.from(element),
                "'$name' would capture the volume keys or reroute audio. The wake screen swallows the volume keys only while " +
                    "it is in front (VolumeKeyGate), and audio routing stays with the system (FR-SES-6, NFR-13).",
            ),
        )
    }

    private companion object {
        val bannedTypes = setOf("MediaSession", "MediaSessionCompat", "VolumeProvider", "VolumeProviderCompat")
        val bannedCalls =
            setOf(
                "setPreferredDevice",
                "setSpeakerphoneOn",
                "setCommunicationDevice",
                "registerMediaButtonEventReceiver",
                "startBluetoothSco",
                "setBluetoothScoOn",
            )
        val bannedProperties = setOf("isSpeakerphoneOn", "isBluetoothScoOn")
        val callModes = listOf("MODE_IN_COMMUNICATION", "MODE_IN_CALL")
    }
}

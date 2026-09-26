package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

/** The only package (and sub-packages) where the system clocks may be read: the Android time adapters. */
internal const val ANDROID_ADAPTER_PACKAGE = "com.yawnandpawn.app.android"

/** ...and only in `:androidApp` sources, so another module cannot borrow the package name. */
internal const val ANDROID_APP_SOURCES = "/androidApp/src/"

/**
 * Time only through the ports `Clock`, `MonotonicClock`, `BootCounter` and `TimeZoneProvider` (AD-3).
 * Reported outside [ANDROID_ADAPTER_PACKAGE] files under [ANDROID_APP_SOURCES]:
 * - `Clock.System`, `System.currentTimeMillis()`, `System.nanoTime()`, `SystemClock` (any use or import of a member),
 *   `TimeZone.currentSystemDefault()` (also via `.Companion`);
 * - java.time / java.util reads: `Instant|LocalDateTime|ZonedDateTime|OffsetDateTime|LocalDate|LocalTime.now()`,
 *   `Clock.system*()`, `ZoneId.systemDefault()`, `TimeZone.getDefault()`, `Calendar.getInstance()`, `Date()`;
 * - static imports of any of these, and aliased imports of the clock types (`import kotlin.time.Clock as C`).
 * Scoped to the app modules by `includes` in `config/detekt/detekt.yml`.
 */
class NoDirectTimeAccess(
    config: Config,
) : Rule(config, "Read time through the Clock, MonotonicClock, BootCounter and TimeZoneProvider ports, not the system clocks.") {
    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        if (expression.isInImportOrPackage()) return
        val receiver = expression.receiverExpression.text.removeSuffix(".Companion")
        val selector = expression.selectorExpression
        val call = selector as? KtCallExpression
        val banned =
            if (call != null) {
                bannedCalls[call.calleeExpression?.text].orEmpty().any { receiver.isName(it) }
            } else {
                selector?.text == "System" && receiver.isName("Clock")
            }
        if (banned) reportIfOutsideAdapters(expression, expression.text)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        // `Date()` with no arguments reads the wall clock; `Date(millis)` does not.
        val noArguments = expression.valueArguments.isEmpty() && expression.lambdaArguments.isEmpty()
        if (expression.calleeExpression?.text == "Date" && noArguments) reportIfOutsideAdapters(expression, "Date()")
    }

    override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
        super.visitSimpleNameExpression(expression)
        if (expression.getReferencedName() == "SystemClock" && !expression.isInImportOrPackage()) {
            reportIfOutsideAdapters(expression, "SystemClock")
        }
    }

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val path = importDirective.importedFqName?.asString() ?: return
        val segments = path.split(".").filterNot { it == "Companion" }
        val member = segments.last()
        val owner = segments.getOrNull(segments.size - 2)
        val staticImport =
            path.startsWith("android.os.SystemClock.") ||
                (owner != null && owner in bannedCalls[member].orEmpty()) ||
                (member == "System" && owner == "Clock")
        val aliasedType = importDirective.aliasName != null && path in aliasBannedTypes
        if (staticImport || aliasedType) reportIfOutsideAdapters(importDirective, importDirective.text)
    }

    private fun String.isName(simpleName: String): Boolean = this == simpleName || endsWith(".$simpleName")

    private fun reportIfOutsideAdapters(
        element: KtElement,
        what: String,
    ) {
        if (element.isInAndroidAdapter()) return
        report(
            Finding(
                Entity.from(element),
                "Direct time access '$what'. Inject the Clock, MonotonicClock, BootCounter or TimeZoneProvider port " +
                    "(tests use the fakes in :testing); only $ANDROID_ADAPTER_PACKAGE adapters in :androidApp may read the system clocks.",
            ),
        )
    }

    private fun KtElement.isInAndroidAdapter(): Boolean {
        val file = containingKtFile
        val name = file.packageFqName.asString()
        val inPackage = name == ANDROID_ADAPTER_PACKAGE || name.startsWith("$ANDROID_ADAPTER_PACKAGE.")
        return inPackage && file.virtualFilePath.replace('\\', '/').contains(ANDROID_APP_SOURCES)
    }

    private companion object {
        /** Call name to the receiver types (simple names) on which it reads the system time or zone. */
        val bannedCalls: Map<String?, Set<String>> =
            mapOf(
                "currentTimeMillis" to setOf("System"),
                "nanoTime" to setOf("System"),
                "currentSystemDefault" to setOf("TimeZone"),
                "getDefault" to setOf("TimeZone"),
                "now" to setOf("Instant", "LocalDateTime", "ZonedDateTime", "OffsetDateTime", "LocalDate", "LocalTime"),
                "system" to setOf("Clock"),
                "systemUTC" to setOf("Clock"),
                "systemDefaultZone" to setOf("Clock"),
                "systemDefault" to setOf("ZoneId"),
                "getInstance" to setOf("Calendar"),
            )

        /** Clock types that may not be imported under another name (the alias would hide them from the checks). */
        val aliasBannedTypes =
            setOf(
                "kotlin.time.Clock",
                "java.lang.System",
                "kotlinx.datetime.TimeZone",
                "android.os.SystemClock",
                "java.util.TimeZone",
                "java.time.Clock",
            )
    }
}

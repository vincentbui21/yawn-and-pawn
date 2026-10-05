package com.yawnandpawn.app.buildlogic

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** A merged manifest of one variant (`debug`, `release`) as XML text. */
data class VariantManifest(
    val variant: String,
    val xml: String,
)

/**
 * NFR-4 / AD-5 / NFR-13 (no device hostage, Stories 1.2 and 2.11): every requested permission must be in
 * `config/permission-allowlist.txt`; `SCHEDULE_EXACT_ALARM` must stop at API 32. Whatever the allowlist says, these
 * always fail:
 * - a hostage permission ([hostageRequests], or any `MANAGE_DEVICE_POLICY_*`);
 * - a component or the application protected by `BIND_ACCESSIBILITY_SERVICE` or `BIND_DEVICE_ADMIN`;
 * - an intent filter with `CATEGORY_HOME` (posing as the launcher);
 * - any `android:lockTaskMode` other than `normal`;
 * - `android:stopWithTask="true"` on `WakeService` (swiping the app away must never end the ring's service).
 *
 * Pure functions; the Gradle task only gathers the input.
 */
object PermissionAllowlist {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val PLATFORM_PREFIX = "android.permission."
    private const val SCHEDULE_EXACT_ALARM = "android.permission.SCHEDULE_EXACT_ALARM"
    private const val EXACT_ALARM_MAX_SDK = "32"
    private const val MANAGE_DEVICE_POLICY_PREFIX = "android.permission.MANAGE_DEVICE_POLICY_"
    private const val CATEGORY_HOME = "android.intent.category.HOME"
    private const val LOCK_TASK_DEFAULT = "normal"
    private const val WAKE_SERVICE = "WakeService"

    private val permissionTags = setOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")
    private val componentTags = setOf("activity", "activity-alias", "service", "receiver", "provider")
    private val hostagePermissions =
        setOf("android.permission.BIND_ACCESSIBILITY_SERVICE", "android.permission.BIND_DEVICE_ADMIN")

    /** Permissions that would let the app hold the phone hostage (overlays, task control, call state, kill, keyguard). */
    private val hostageRequests =
        setOf(
            "SYSTEM_ALERT_WINDOW",
            "READ_PHONE_STATE",
            "REORDER_TASKS",
            "DISABLE_KEYGUARD",
            "PACKAGE_USAGE_STATS",
            "KILL_BACKGROUND_PROCESSES",
        ).map(::qualify).toSet()

    private fun isHostageRequest(permission: String): Boolean =
        permission in hostageRequests || permission.startsWith(MANAGE_DEVICE_POLICY_PREFIX)

    /**
     * Allowlist entries, one permission per line; `#` starts a comment. Names without a dot are
     * platform permissions: `CAMERA` means `android.permission.CAMERA`.
     */
    fun parse(text: String): Set<String> =
        text
            .lines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map(::qualify)
            .toSet()

    fun qualify(name: String): String = if ('.' in name) name else PLATFORM_PREFIX + name

    fun verify(
        allowlist: Set<String>,
        manifests: List<VariantManifest>,
    ): List<String> = manifests.flatMap { verifyManifest(allowlist, it) }

    private fun verifyManifest(
        allowlist: Set<String>,
        manifest: VariantManifest,
    ): List<String> {
        val root = parseXml(manifest.xml)
        val variant = manifest.variant
        val violations = mutableListOf<String>()
        // Components without android:permission inherit the one on <application>.
        val applicationPermission =
            root
                .descendants()
                .firstOrNull { it.tagName == "application" }
                ?.androidAttribute("permission")
                .orEmpty()
        root.descendants().forEach { element ->
            val tag = element.tagName
            val name = element.androidAttribute("name")
            if (tag in permissionTags) {
                if (isHostageRequest(name)) {
                    violations += "$variant: $tag '$name' is never allowed (device hostage, NFR-13)"
                } else if (name !in allowlist) {
                    violations += "$variant: $tag '$name' is not in config/permission-allowlist.txt"
                }
                val maxSdk = element.androidAttribute("maxSdkVersion")
                if (name == SCHEDULE_EXACT_ALARM && maxSdk != EXACT_ALARM_MAX_SDK) {
                    violations += "$variant: '$name' must declare android:maxSdkVersion=\"$EXACT_ALARM_MAX_SDK\" " +
                        "(found ${maxSdk.ifEmpty { "none" }}); API 33+ uses USE_EXACT_ALARM"
                }
            }
            val permission =
                element.androidAttribute("permission").ifEmpty {
                    if (tag in componentTags) applicationPermission else ""
                }
            if ((tag in componentTags || tag == "application") && permission in hostagePermissions) {
                violations += "$variant: $tag '$name' is protected by '$permission' (device hostage, AD-5)"
            }
            violations += componentViolations(variant, element)
        }
        return violations
    }

    /** The launcher pose, lock-task mode and `stopWithTask` rules for [element] (Story 2.11). */
    private fun componentViolations(
        variant: String,
        element: Element,
    ): List<String> {
        val tag = element.tagName
        val name = element.androidAttribute("name")
        val violations = mutableListOf<String>()
        val lockTaskMode = element.androidAttribute("lockTaskMode")
        if (element.hasAttributeNS(ANDROID_NS, "lockTaskMode") && lockTaskMode != LOCK_TASK_DEFAULT) {
            violations += "$variant: $tag '$name' sets android:lockTaskMode=\"$lockTaskMode\" (device hostage, AD-5)"
        }
        if (tag == "category" && name == CATEGORY_HOME) {
            val owner =
                generateSequence(element.parentNode as? Element) { it.parentNode as? Element }
                    .firstOrNull { it.tagName in componentTags }
            val ownerTag = owner?.tagName ?: "intent-filter"
            val ownerName = owner?.androidAttribute("name").orEmpty()
            violations += "$variant: $ownerTag '$ownerName' declares CATEGORY_HOME (posing as the launcher, NFR-13)"
        }
        val stopsWithTask = element.androidAttribute("stopWithTask") == "true"
        if (tag == "service" && stopsWithTask && name.substringAfterLast('.') == WAKE_SERVICE) {
            violations += "$variant: service '$name' sets android:stopWithTask=\"true\" " +
                "(a swipe from Recents must never end the ring, NFR-13)"
        }
        return violations
    }

    private fun parseXml(xml: String): Element {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
    }

    private fun Element.descendants(): List<Element> {
        val nodes = getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.androidAttribute(name: String): String = getAttributeNS(ANDROID_NS, name)
}

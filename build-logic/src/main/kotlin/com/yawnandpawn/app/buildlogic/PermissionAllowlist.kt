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
 * NFR-4 / AD-5 (no device hostage): every requested permission must be in
 * `config/permission-allowlist.txt`; `SCHEDULE_EXACT_ALARM` must stop at API 32; no accessibility
 * service, device admin or lock-task mode. Pure functions; the Gradle task only gathers the input.
 */
object PermissionAllowlist {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val PLATFORM_PREFIX = "android.permission."
    private const val SCHEDULE_EXACT_ALARM = "android.permission.SCHEDULE_EXACT_ALARM"
    private const val EXACT_ALARM_MAX_SDK = "32"

    private val permissionTags = setOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")
    private val componentTags = setOf("activity", "activity-alias", "service", "receiver", "provider")
    private val hostagePermissions =
        setOf("android.permission.BIND_ACCESSIBILITY_SERVICE", "android.permission.BIND_DEVICE_ADMIN")

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
                if (name !in allowlist) {
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
            if (element.hasAttributeNS(ANDROID_NS, "lockTaskMode")) {
                violations += "$variant: $tag '$name' sets android:lockTaskMode (device hostage, AD-5)"
            }
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

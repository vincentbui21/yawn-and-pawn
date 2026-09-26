package com.yawnandpawn.app.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PermissionAllowlistTest {
    private val allowlist =
        PermissionAllowlist.parse(
            """
            # platform
            INTERNET
            POST_NOTIFICATIONS
            SCHEDULE_EXACT_ALARM  # bounded
            USE_EXACT_ALARM
            com.android.vending.BILLING
            com.yawnandpawn.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
            """.trimIndent(),
        )

    private fun manifest(
        body: String,
        application: String = "",
    ): String =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.yawnandpawn.app">
            <uses-permission android:name="android.permission.INTERNET" />
            <uses-permission android:name="com.yawnandpawn.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" />
            $body
            <application android:name=".YawnAndPawnApp">
                <activity android:name=".MainActivity" android:exported="true" />
                $application
            </application>
        </manifest>
        """.trimIndent()

    private fun verify(
        xml: String,
        variant: String = "debug",
    ) = PermissionAllowlist.verify(allowlist, listOf(VariantManifest(variant, xml)))

    @Test
    fun `short names mean platform permissions`() {
        assertTrue("android.permission.INTERNET" in allowlist)
        assertTrue("com.android.vending.BILLING" in allowlist)
        assertEquals("android.permission.CAMERA", PermissionAllowlist.qualify("CAMERA"))
    }

    @Test
    fun `a manifest with only listed permissions passes`() {
        val xml = manifest("""<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />""")

        assertEquals(emptyList(), verify(xml))
    }

    @Test
    fun `READ_PHONE_STATE fails naming the permission and the variant`() {
        val xml = manifest("""<uses-permission android:name="android.permission.READ_PHONE_STATE" />""")

        assertEquals(
            listOf("release: uses-permission 'android.permission.READ_PHONE_STATE' is not in config/permission-allowlist.txt"),
            verify(xml, variant = "release"),
        )
    }

    @Test
    fun `SYSTEM_ALERT_WINDOW fails naming the permission`() {
        val xml = manifest("""<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />""")

        val violations = verify(xml)

        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("'android.permission.SYSTEM_ALERT_WINDOW'"))
    }

    @Test
    fun `a permission requested with uses-permission-sdk-23 is checked too`() {
        val xml = manifest("""<uses-permission-sdk-23 android:name="android.permission.READ_CONTACTS" />""")

        assertTrue(verify(xml).single().contains("uses-permission-sdk-23 'android.permission.READ_CONTACTS'"))
    }

    @Test
    fun `SCHEDULE_EXACT_ALARM bounded at API 32 passes`() {
        val xml =
            manifest(
                """<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />""",
            )

        assertEquals(emptyList(), verify(xml))
    }

    @Test
    fun `SCHEDULE_EXACT_ALARM without maxSdkVersion fails naming the missing bound`() {
        val xml = manifest("""<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />""")

        val violation = verify(xml).single()

        assertTrue(violation.contains("must declare android:maxSdkVersion=\"32\""))
        assertTrue(violation.contains("found none"))
    }

    @Test
    fun `SCHEDULE_EXACT_ALARM with a different bound fails`() {
        val xml =
            manifest(
                """<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="33" />""",
            )

        assertTrue(verify(xml).single().contains("found 33"))
    }

    @Test
    fun `an accessibility service fails naming the component`() {
        val xml =
            manifest(
                body = "",
                application =
                    """<service android:name=".Watcher" android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE" />""",
            )

        assertEquals(
            listOf("debug: service '.Watcher' is protected by 'android.permission.BIND_ACCESSIBILITY_SERVICE' (device hostage, AD-5)"),
            verify(xml),
        )
    }

    @Test
    fun `a device admin receiver fails naming the component`() {
        val xml =
            manifest(
                body = "",
                application = """<receiver android:name=".Admin" android:permission="android.permission.BIND_DEVICE_ADMIN" />""",
            )

        assertTrue(verify(xml).single().contains("receiver '.Admin' is protected by 'android.permission.BIND_DEVICE_ADMIN'"))
    }

    @Test
    fun `a hostage permission on the application fails it and every component inheriting it`() {
        val xml =
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:name=".App" android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
                    <service android:name=".Watcher" />
                    <activity android:name=".Own" android:permission="android.permission.INTERNET" />
                </application>
            </manifest>
            """.trimIndent()

        assertEquals(
            listOf(
                "debug: application '.App' is protected by 'android.permission.BIND_ACCESSIBILITY_SERVICE' (device hostage, AD-5)",
                "debug: service '.Watcher' is protected by 'android.permission.BIND_ACCESSIBILITY_SERVICE' (device hostage, AD-5)",
            ),
            verify(xml),
        )
    }

    @Test
    fun `lock-task mode fails naming the component`() {
        val xml =
            manifest(
                body = "",
                application = """<activity android:name=".Kiosk" android:lockTaskMode="if_whitelisted" />""",
            )

        assertEquals(listOf("debug: activity '.Kiosk' sets android:lockTaskMode (device hostage, AD-5)"), verify(xml))
    }

    @Test
    fun `violations from every variant are reported`() {
        val bad = manifest("""<uses-permission android:name="android.permission.READ_PHONE_STATE" />""")

        val violations =
            PermissionAllowlist.verify(allowlist, listOf(VariantManifest("debug", bad), VariantManifest("release", bad)))

        assertEquals(listOf("debug", "release"), violations.map { it.substringBefore(':') })
    }
}

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
            listOf("release: uses-permission 'android.permission.READ_PHONE_STATE' is never allowed (device hostage, NFR-13)"),
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

        assertEquals(listOf("debug: activity '.Kiosk' sets android:lockTaskMode=\"if_whitelisted\" (device hostage, AD-5)"), verify(xml))
    }

    @Test
    fun `every hostage permission fails even when it is on the allowlist (Story 2-11)`() {
        val hostage =
            listOf(
                "SYSTEM_ALERT_WINDOW",
                "READ_PHONE_STATE",
                "REORDER_TASKS",
                "DISABLE_KEYGUARD",
                "PACKAGE_USAGE_STATS",
                "KILL_BACKGROUND_PROCESSES",
                "MANAGE_DEVICE_POLICY_LOCK_TASK",
                "MANAGE_DEVICE_POLICY_APPS_CONTROL",
            )
        val permissive = allowlist + hostage.map(PermissionAllowlist::qualify)

        hostage.forEach { permission ->
            val xml = manifest("""<uses-permission android:name="android.permission.$permission" />""")

            assertEquals(
                listOf("debug: uses-permission 'android.permission.$permission' is never allowed (device hostage, NFR-13)"),
                PermissionAllowlist.verify(permissive, listOf(VariantManifest("debug", xml))),
                permission,
            )
        }
    }

    @Test
    fun `an intent filter with CATEGORY_HOME fails naming the component (Story 2-11)`() {
        val xml =
            manifest(
                body = "",
                // One line: a multi-line block would change the manifest template's common indent.
                application =
                    """<activity android:name=".Launcher" android:exported="true"><intent-filter>""" +
                        """<action android:name="android.intent.action.MAIN" />""" +
                        """<category android:name="android.intent.category.HOME" />""" +
                        """<category android:name="android.intent.category.DEFAULT" />""" +
                        """</intent-filter></activity>""",
            )

        assertEquals(listOf("debug: activity '.Launcher' declares CATEGORY_HOME (posing as the launcher, NFR-13)"), verify(xml))
    }

    @Test
    fun `an intent filter with CATEGORY_SECONDARY_HOME fails too (Story 2-11)`() {
        val xml =
            manifest(
                body = "",
                application =
                    """<activity android:name=".Second" android:exported="true"><intent-filter>""" +
                        """<action android:name="android.intent.action.MAIN" />""" +
                        """<category android:name="android.intent.category.SECONDARY_HOME" />""" +
                        """</intent-filter></activity>""",
            )

        assertEquals(
            listOf("debug: activity '.Second' declares CATEGORY_SECONDARY_HOME (posing as the launcher, NFR-13)"),
            verify(xml),
        )
    }

    @Test
    fun `looking the launcher up in queries passes (Story 2-11)`() {
        val xml =
            manifest(
                body =
                    """<queries><intent><action android:name="android.intent.action.MAIN" />""" +
                        """<category android:name="android.intent.category.HOME" /></intent></queries>""",
            )

        assertEquals(emptyList(), verify(xml))
    }

    @Test
    fun `any lock-task mode but the default fails, and normal passes (Story 2-11)`() {
        val modes = listOf("never", "if_whitelisted", "always")

        modes.forEach { mode ->
            val xml = manifest(body = "", application = """<activity android:name=".Kiosk" android:lockTaskMode="$mode" />""")
            assertEquals(1, verify(xml).size, mode)
        }
        val normal = manifest(body = "", application = """<activity android:name=".Own" android:lockTaskMode="normal" />""")
        assertEquals(emptyList(), verify(normal))
    }

    @Test
    fun `stopWithTask on the wake service fails, on another service it passes (Story 2-11)`() {
        val wake =
            manifest(
                body = "",
                application =
                    """<service android:name="com.yawnandpawn.app.android.wake.WakeService" android:stopWithTask="true" />""",
            )
        val other = manifest(body = "", application = """<service android:name=".Sync" android:stopWithTask="true" />""")
        val wakeDefault = manifest(body = "", application = """<service android:name=".android.wake.WakeService" />""")

        assertEquals(
            listOf(
                "debug: service 'com.yawnandpawn.app.android.wake.WakeService' sets android:stopWithTask=\"true\" " +
                    "(a swipe from Recents must never end the ring, NFR-13)",
            ),
            verify(wake),
        )
        assertEquals(emptyList(), verify(other))
        assertEquals(emptyList(), verify(wakeDefault))
    }

    @Test
    fun `stopWithTask on the wake service fails for a resource reference and passes only for false (Story 2-11)`() {
        fun wake(value: String) =
            manifest(
                body = "",
                application = """<service android:name=".android.wake.WakeService" android:stopWithTask="$value" />""",
            )

        assertEquals(
            listOf(
                "debug: service '.android.wake.WakeService' sets android:stopWithTask=\"@bool/stop\" " +
                    "(a swipe from Recents must never end the ring, NFR-13)",
            ),
            verify(wake("@bool/stop")),
        )
        assertEquals(1, verify(wake("TRUE")).size)
        assertEquals(emptyList(), verify(wake("false")))
    }

    @Test
    fun `violations from every variant are reported`() {
        val bad = manifest("""<uses-permission android:name="android.permission.READ_PHONE_STATE" />""")

        val violations =
            PermissionAllowlist.verify(allowlist, listOf(VariantManifest("debug", bad), VariantManifest("release", bad)))

        assertEquals(listOf("debug", "release"), violations.map { it.substringBefore(':') })
    }
}

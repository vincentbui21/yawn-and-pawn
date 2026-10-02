package com.yawnandpawn.app.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 1.18: the release-content rules find every kind of debug-only item, and nothing in a clean release build. */
class ReleaseContentTest {
    private val cleanManifest =
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.yawnandpawn.app">
            <application android:label="@string/app_name">
                <activity android:name="com.yawnandpawn.app.MainActivity" android:exported="true" />
                <receiver android:name="com.yawnandpawn.app.android.AlarmFiredReceiver" android:exported="false" />
            </application>
        </manifest>
        """.trimIndent()

    private val cleanClasses =
        listOf(
            ReleaseClass("com/yawnandpawn/app/MainActivity.class", "Yawn & Pawn".toByteArray()),
            ReleaseClass("com/yawnandpawn/app/android/wake/WakeService.class", "com.yawnandpawn.app.action.WAKE_TEST".toByteArray()),
        )

    private val cleanResources = mapOf("values/strings.xml" to "<resources><string name=\"app_name\">Yawn &amp; Pawn</string></resources>")

    @Test
    fun `a clean release build has no violations`() {
        assertEquals(emptyList(), ReleaseContent.violations(cleanManifest, cleanClasses, cleanResources))
    }

    @Test
    fun `the fire-now hook in the manifest is found`() {
        val manifest =
            cleanManifest.replace(
                "</application>",
                """<provider android:name="com.yawnandpawn.app.debug.fire.DebugFireProvider" />
                <receiver android:name=".DebugFireReceiver"><intent-filter>
                <action android:name="com.yawnandpawn.app.debug.FIRE" /></intent-filter></receiver></application>""",
            )

        val found = ReleaseContent.violations(manifest, cleanClasses, cleanResources)

        listOf("DebugFireReceiver", "DebugFireProvider", "com.yawnandpawn.app.debug.FIRE").forEach { marker ->
            assertTrue(found.any { marker in it }, "$marker in $found")
        }
    }

    @Test
    fun `the preview and showcase activities and the preview label in the manifest are found`() {
        val manifest =
            cleanManifest.replace(
                "</application>",
                """<activity android:name="com.yawnandpawn.app.debug.ThemeShowcaseActivity" />
                <activity android:name="com.yawnandpawn.app.debug.preview.PreviewActivity"
                    android:label="@string/preview_launcher_label" /></application>""",
            )

        val found = ReleaseContent.violations(manifest, cleanClasses, cleanResources)

        listOf("ThemeShowcase", ".debug.preview.", "preview_launcher_label").forEach { marker ->
            assertTrue(found.any { marker in it }, "$marker in $found")
        }
    }

    @Test
    fun `debug-only classes and the fire action inside a class are found`() {
        val classes =
            cleanClasses +
                listOf(
                    ReleaseClass("com/yawnandpawn/app/debug/preview/PreviewCatalog.class", ByteArray(0)),
                    ReleaseClass("com/yawnandpawn/app/debug/ThemeShowcaseKt.class", ByteArray(0)),
                    ReleaseClass("com/yawnandpawn/app/other/DebugFireLeak.class", ByteArray(0)),
                    ReleaseClass("com/yawnandpawn/app/Leak.class", "x com.yawnandpawn.app.debug.FIRE x".toByteArray()),
                    ReleaseClass("com/yawnandpawn/app/Label.class", "Yawn & Pawn Preview".toByteArray()),
                )

        val found = ReleaseContent.violations(cleanManifest, classes, cleanResources)

        assertEquals(5, found.size, "$found")
        assertTrue(found.any { "debug/preview/PreviewCatalog" in it })
        assertTrue(found.any { "ThemeShowcaseKt" in it })
        assertTrue(found.any { "DebugFireLeak" in it })
        assertTrue(found.any { "Leak.class contains 'com.yawnandpawn.app.debug.FIRE'" in it })
        assertTrue(found.any { "Label.class contains 'Yawn & Pawn Preview'" in it })
    }

    @Test
    fun `the preview launcher label in a release resource is found`() {
        val resources =
            cleanResources +
                ("values/preview.xml" to "<resources><string name=\"preview_launcher_label\">Yawn &amp; Pawn Preview</string></resources>")

        val found = ReleaseContent.violations(cleanManifest, cleanClasses, resources)

        assertEquals(2, found.size, "$found")
        assertTrue(found.all { "values/preview.xml" in it })
    }
}

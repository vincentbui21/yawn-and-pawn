package com.yawnandpawn.app.android.telemetry

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.crash.isFirebaseConfigured
import com.yawnandpawn.app.core.telemetry.ConsentGatedTelemetry
import com.yawnandpawn.app.core.telemetry.Telemetry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Story 5.6 (AD-15): the merged manifest keeps Analytics off with every ad signal denied and no ad permission, the app
 * never sets a user id or user property, and the app binds Analytics only with a Firebase configuration.
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsManifestTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the merged manifest turns Analytics collection, ad id, SSAID, storage and ad consents and screen reporting off`() {
        @Suppress("DEPRECATION")
        val info: ApplicationInfo = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)

        listOf(
            "firebase_analytics_collection_enabled",
            "google_analytics_adid_collection_enabled",
            "google_analytics_ssaid_collection_enabled",
            "google_analytics_default_allow_analytics_storage",
            "google_analytics_default_allow_ad_storage",
            "google_analytics_default_allow_ad_user_data",
            "google_analytics_default_allow_ad_personalization_signals",
            "google_analytics_automatic_screen_reporting_enabled",
        ).forEach { flag ->
            assertTrue(info.metaData.containsKey(flag), "$flag is declared")
            assertFalse(info.metaData.getBoolean(flag, true), "$flag is false")
        }
    }

    @Test
    fun `the merged manifest has no advertising id, ad services or install referrer permission`() {
        @Suppress("DEPRECATION")
        val requested =
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
                .toList()

        listOf(
            "com.google.android.gms.permission.AD_ID",
            "android.permission.ACCESS_ADSERVICES_AD_ID",
            "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
            "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE",
        ).forEach { permission -> assertFalse(permission in requested, "$permission in $requested") }
    }

    /** Review fix 8: the advertising id library is excluded from Analytics, so it is not on the app's classpath. */
    @Test
    fun `the advertising id library is not on the classpath`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient") }
        val build = File("build.gradle.kts").readText()
        assertTrue(build.contains("exclude(group = \"com.google.android.gms\", module = \"play-services-ads-identifier\")"))
        assertFalse(
            File(
                "../config/dependency-allowlist.txt",
            ).readLines().any { it.trim() == "com.google.android.gms:play-services-ads-identifier" },
        )
    }

    @Test
    fun `no app code sets a user id or a user property`() {
        val sources =
            File("src/main")
                .walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "java", "xml") }
                .toList()
        assertTrue(sources.size > 10, "the scan sees the app sources (${sources.size})")

        val offending = sources.filter { file -> listOf("setUserId", "setUserProperty").any { it in file.readText() } }

        assertEquals(emptyList(), offending.map { it.path })
    }

    @Test
    fun `the app binds consent-gated Analytics only with a Firebase configuration, else no telemetry and a no-op sink`() {
        val koin = GlobalContext.get()
        if (isFirebaseConfigured(context)) {
            assertIs<ConsentGatedTelemetry>(koin.get<Telemetry>())
            assertIs<FirebaseAnalyticsSink>(koin.get<AnalyticsSink>())
        } else {
            assertSame(Telemetry.None, koin.get<Telemetry>())
            assertSame(NoOpAnalyticsSink, koin.get<AnalyticsSink>())
        }
        assertSame(koin.get<AnalyticsConsentGate>(), koin.get<AnalyticsConsentGate>())
    }
}

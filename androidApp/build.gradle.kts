import com.yawnandpawn.app.buildlogic.versionCodeOf
import java.util.Properties

// :androidApp — the Android application: manifest, platform adapters, Koin wiring, screenshot tests.
// Uses AGP 9 built-in Kotlin (no separate Kotlin Android plugin).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.roborazzi)
}

// Firebase (Story 1.19, AD-15): the google-services and Crashlytics plugins apply only when a google-services.json is
// here; it is never committed. Each build type has its own project: src/release/google-services.json (production) and
// src/debug/google-services.json (debug) win over the module-root file, the google-services plugin's own lookup order.
// CI writes the debug one from GOOGLE_SERVICES_JSON_DEBUG, release.yml the production one from
// GOOGLE_SERVICES_JSON_RELEASE. A build type with no file builds without Firebase (the no-op crash reporter, a plugin
// warning), so qualityGate still passes with none at all.
val firebaseProjectIds = mapOf("debug" to "yawn-and-pawn-debug", "release" to "yawn-and-pawn")
val googleServicesJsons =
    firebaseProjectIds.keys.associateWith { buildType ->
        listOf(file("src/$buildType/google-services.json"), file("google-services.json")).firstOrNull { it.exists() }
    }
if (googleServicesJsons.values.any { it != null }) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
    configure<com.google.gms.googleservices.GoogleServicesPlugin.GoogleServicesPluginConfig> {
        missingGoogleServicesStrategy = com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy.WARN
    }
    // Without a file there is no Firebase app id to upload that build type's R8 mapping to (the CI release bundle).
    val withoutFirebase = googleServicesJsons.filterValues { it == null }.keys
    android.buildTypes.matching { it.name in withoutFirebase }.configureEach {
        (this as ExtensionAware).configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
            mappingFileUploadEnabled = false
        }
    }
}

// A build type that would use the other build type's Firebase project fails before it builds anything (0.1.9): a
// release build must never report to the debug project, nor a debug build to the production one.
googleServicesJsons.forEach { (buildType, json) ->
    if (json == null) return@forEach
    // Read through a provider (configuration-cache safe). A file that is not valid JSON is left to the google-services
    // plugin, which fails only that build type.
    val text = providers.fileContents(layout.projectDirectory.file(json.relativeTo(projectDir).path)).asText.get()
    val parsed = runCatching { groovy.json.JsonSlurper().parseText(text) }.getOrNull() as? Map<*, *>
    val projectInfo = parsed?.get("project_info") as? Map<*, *>
    val wrongProject = projectInfo?.get("project_id").takeIf { it in firebaseProjectIds.minus(buildType).values } ?: return@forEach
    val problem =
        "The $buildType build would use ${json.relativeTo(rootDir).invariantSeparatorsPath}, the Firebase project " +
            "'$wrongProject'. Put the '${firebaseProjectIds.getValue(buildType)}' project's google-services.json in " +
            "androidApp/src/$buildType/ (docs/ci-release.md)."
    val variant = buildType.replaceFirstChar { it.uppercase() }
    val check =
        tasks.register("checkFirebaseProject$variant") {
            group = "verification"
            description = "Fails when the $buildType build would report to another build type's Firebase project."
            doLast { throw GradleException(problem) }
        }
    tasks.matching { it.name == "pre${variant}Build" }.configureEach { dependsOn(check) }
}

// The release workflow passes the tag version (-Pyawnandpawn.versionName=X.Y.Z); local builds use the default.
val appVersionName = providers.gradleProperty("yawnandpawn.versionName").getOrElse("0.1.0")

// Release signing comes only from the environment (GitHub Actions secrets in release.yml, see
// docs/ci-release.md). With none of the four values the release build stays unsigned; with only
// some of them the build fails, so a misconfigured release never ships unsigned by accident.
val uploadSigning =
    listOf("UPLOAD_KEYSTORE_FILE", "UPLOAD_KEYSTORE_PASSWORD", "UPLOAD_KEY_ALIAS", "UPLOAD_KEY_PASSWORD")
        .associateWith { providers.environmentVariable(it).orNull.orEmpty() }
        .let { values ->
            val missing = values.filterValues { it.isBlank() }.keys
            when {
                missing.isEmpty() -> values
                missing.size == values.size -> null
                else -> throw GradleException("Release signing is partly configured; missing: ${missing.joinToString()}")
            }
        }

// Story 4.17: the support address and Google Play's order history come from config/app-links.properties, so changing
// either is one edit there. Read through a provider (configuration-cache safe); a missing or blank key fails the build.
val appLinks =
    Properties().apply {
        load(
            providers
                .fileContents(rootProject.layout.projectDirectory.file("config/app-links.properties"))
                .asText
                .get()
                .reader(),
        )
    }

fun appLink(key: String): String =
    appLinks.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() && '"' !in it && '\\' !in it }
        ?: throw GradleException("config/app-links.properties: $key is missing or not a plain value")

kotlin {
    jvmToolchain(
        libs.versions.jvm.toolchain
            .get()
            .toInt(),
    )
}

android {
    namespace = "com.yawnandpawn.app"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "com.yawnandpawn.app"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        versionName = appVersionName
        // major * 10000 + minor * 100 + patch (unit-tested in build-logic and :core AppVersion).
        versionCode = versionCodeOf(appVersionName)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPPORT_EMAIL", "\"${appLink("support.email")}\"")
        buildConfigField("String", "PLAY_ORDER_HISTORY_URL", "\"${appLink("play.orderHistory")}\"")
        // Story 5.7: the privacy policy, terms and support pages on GitHub Pages (docs/site/).
        buildConfigField("String", "PRIVACY_URL", "\"${appLink("privacy.url")}\"")
        buildConfigField("String", "TERMS_URL", "\"${appLink("terms.url")}\"")
        buildConfigField("String", "SUPPORT_URL", "\"${appLink("support.url")}\"")
        // 0.1.7 polish: the site's home page, About's "Website" row.
        buildConfigField("String", "WEBSITE_URL", "\"${appLink("index.url")}\"")
    }

    signingConfigs {
        if (uploadSigning != null) {
            create("upload") {
                storeFile = file(uploadSigning.getValue("UPLOAD_KEYSTORE_FILE"))
                storePassword = uploadSigning.getValue("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = uploadSigning.getValue("UPLOAD_KEY_ALIAS")
                keyPassword = uploadSigning.getValue("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // No applicationIdSuffix: Play Billing test purchases need the real id.
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (uploadSigning != null) {
                signingConfig = signingConfigs.getByName("upload")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Story 4.17: the app links above.
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Gradle's 512 MB default ran out late in the run (OutOfMemoryError in the last screenshot tests) once Story
            // 3.3 added its wake tests. A fresh JVM every 40 test classes (about 120 in all) drops what Robolectric's
            // sandboxes and WorkManager's process-wide instance keep, so the run stays inside the CI runner's memory
            // (2 vCPU, about 8 GB with the Gradle and Kotlin daemons); one JVM at a time.
            all {
                it.maxHeapSize = "1g"
                it.forkEvery = 40
                it.maxParallelForks = 1
            }
        }
        // Gradle Managed Device for CI: ./gradlew :androidApp:atdApi34DebugAndroidTest (needs KVM).
        managedDevices {
            localDevices {
                create("atdApi34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }
}

roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

dependencies {
    implementation(project(":composeApp"))
    implementation(project(":data"))
    implementation(project(":core"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.koin.android)
    // The wake screen (Story 1.14) and the debug theme showcase (src/debug). Same artifacts :composeApp already puts on
    // the runtime classpath.
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.components.resources)
    // Story 1.19: Crashlytics behind the core CrashReporter port; started only after the user unlocks.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    // Story 5.6 (AD-15): Analytics behind the core Telemetry port, collecting only with the user's consent, after unlock.
    // The advertising id library is left out (review fix 8): the app never reads the ad id (the manifest turns its
    // collection off), so it has no business on the classpath (AnalyticsManifestTest checks it is absent).
    implementation(libs.firebase.analytics) {
        exclude(group = "com.google.android.gms", module = "play-services-ads-identifier")
    }
    // Story 3.10: the QR/Barcode check. CameraX shows the viewfinder and feeds frames to the bundled ML Kit barcode
    // model (on device, no model download through Play services); no frame is ever stored.
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode.scanning)
    // Story 7.10: the printable QR. ZXing core only makes the code (pure Java, no network); the system prints it.
    implementation(libs.zxing.core)
    // Story 4.3 (AD-17): deferred non-alarm work (the price refresh). Started on demand after the first unlock only;
    // the default initializer is removed from the merged manifest.
    implementation(libs.workmanager.runtime)
    // Story 4.12 (AD-7): Play Billing Library 9.1 behind BillingClientFacade. It connects only after the first unlock
    // (AD-15). Its transitive location/places libraries are allowed (owner decision 3, 2026-10-08) and declared for the
    // Data safety form; the app never calls a location API and declares no location permission.
    implementation(libs.play.billing)

    testImplementation(project(":testing"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.workmanager.testing)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Story 3.12: Compose accessibility checks in the managed-device F5 test (FallbackTalkBackDeviceTest).
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    // Story 2.5: the notification-shade return test on the managed device.
}

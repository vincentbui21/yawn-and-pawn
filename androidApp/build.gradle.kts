import com.yawnandpawn.app.buildlogic.versionCodeOf

// :androidApp — the Android application: manifest, platform adapters, Koin wiring, screenshot tests.
// Uses AGP 9 built-in Kotlin (no separate Kotlin Android plugin).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.roborazzi)
}

// Firebase (Story 1.19, AD-15): the google-services and Crashlytics plugins apply only when google-services.json is
// here (CI writes it from the GOOGLE_SERVICES_JSON_* secrets; it is never committed). Without it the app has no Firebase
// configuration and binds the no-op crash reporter, and qualityGate still passes.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
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
    // Story 1.19: Crashlytics behind the core CrashReporter port; started only after the user unlocks. No Analytics.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    // Story 3.10: the QR/Barcode check. CameraX shows the viewfinder and feeds frames to the bundled ML Kit barcode
    // model (on device, no model download through Play services); no frame is ever stored.
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode.scanning)
    // Story 4.3 (AD-17): deferred non-alarm work (the price refresh). Started on demand after the first unlock only;
    // the default initializer is removed from the merged manifest.
    implementation(libs.workmanager.runtime)

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

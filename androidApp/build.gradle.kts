import com.yawnandpawn.app.buildlogic.versionCodeOf

// :androidApp — the Android application: manifest, platform adapters, Koin wiring, screenshot tests.
// Uses AGP 9 built-in Kotlin (no separate Kotlin Android plugin).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.roborazzi)
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

    // Debug-only theme showcase (src/debug). Same artifacts :composeApp already puts on the runtime classpath.
    debugImplementation(libs.compose.foundation)
    debugImplementation(libs.compose.material3)
    debugImplementation(libs.compose.components.resources)

    testImplementation(project(":testing"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
}

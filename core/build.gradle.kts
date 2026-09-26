// :core — platform-free domain (AD-1). commonMain only, jvm() target only (iOS deferred).
// Allowed dependencies: Kotlin stdlib, kotlinx-coroutines, kotlinx-datetime, kotlinx-serialization.
// No Koin: core classes take constructor parameters and are wired in :androidApp (AD-13).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(
        libs.versions.jvm.toolchain
            .get()
            .toInt(),
    )
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            // api: TimeZoneProvider and AlarmRule expose kotlinx-datetime types to :androidApp and :composeApp.
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

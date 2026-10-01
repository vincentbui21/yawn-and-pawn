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
            // api: AlarmRepository.observeAll exposes Flow.
            api(libs.kotlinx.coroutines.core)
            // api: TimeZoneProvider and AlarmRule expose kotlinx-datetime types to :androidApp and :composeApp.
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The wake-session package needs its own 90% line coverage (Story 1.11), on top of the root rule for all of :core.
// `koverVerifySession` runs inside the root `koverVerify` that qualityGate uses.
kover {
    currentProject {
        createVariant("session") {
            add("jvm")
        }
    }
    reports {
        variant("session") {
            filters {
                includes {
                    packages("com.yawnandpawn.app.core.session")
                }
            }
            verify {
                rule("core.session line coverage") {
                    minBound(90)
                }
            }
        }
    }
}

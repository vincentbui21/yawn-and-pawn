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

// The wake-session package (Story 1.11), the check plugins (Story 3.1), billing (Story 4.2), the commitment lock
// (Story 4.4), "Delete all data" (Story 5.9) and core.stats (Stories 3.13 and 6.1) need their own 90% line coverage,
// on top of the root rule for all of :core. `koverVerifySession`, `koverVerifyChecks`, `koverVerifyBilling`, `koverVerifyConfig`,
// `koverVerifyReset` and `koverVerifyStats` run inside the root `koverVerify` that qualityGate uses.
kover {
    currentProject {
        createVariant("session") {
            add("jvm")
        }
        createVariant("checks") {
            add("jvm")
        }
        createVariant("billing") {
            add("jvm")
        }
        createVariant("config") {
            add("jvm")
        }
        createVariant("reset") {
            add("jvm")
        }
        createVariant("stats") {
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
        variant("checks") {
            filters {
                includes {
                    packages("com.yawnandpawn.app.core.checks")
                }
            }
            verify {
                rule("core.checks line coverage") {
                    minBound(90)
                }
            }
        }
        variant("billing") {
            filters {
                includes {
                    packages("com.yawnandpawn.app.core.billing")
                }
            }
            verify {
                rule("core.billing line coverage") {
                    minBound(90)
                }
            }
        }
        variant("config") {
            filters {
                includes {
                    packages("com.yawnandpawn.app.core.config")
                }
            }
            verify {
                rule("core.config line coverage") {
                    minBound(90)
                }
            }
        }
        variant("reset") {
            filters {
                includes {
                    packages("com.yawnandpawn.app.core.reset")
                }
            }
            verify {
                rule("core.reset line coverage") {
                    minBound(90)
                }
            }
        }
        variant("stats") {
            filters {
                includes {
                    packages("com.yawnandpawn.app.core.stats")
                }
            }
            verify {
                rule("core.stats line coverage") {
                    minBound(90)
                }
            }
        }
    }
}

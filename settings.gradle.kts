pluginManagement {
    includeBuild("build-logic")
    // DESIGN.md -> PpsTokens.kt generator (Story 1.3). An included build, not a subproject (AD-1 graph).
    includeBuild("tools/tokens")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Auto-provisions JDK 17 on machines without one (CI, second laptop). Machines with a
    // local JDK registered in ~/.gradle/gradle.properties never download anything.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "yawn-and-pawn"

// Play catalogue tool (Story 4.1): a JVM command-line tool the root `playCatalog` task runs in its own JVM. An included
// build (not a subproject, AD-1 graph), so the Play Developer API client is on no build-script or app classpath.
includeBuild("tools/play-catalog")

include(":core")
include(":data")
include(":composeApp")
include(":androidApp")
include(":testing")

// Build-only module: custom detekt rules.
include(":detekt-rules")
project(":detekt-rules").projectDir = file("config/detekt-rules")

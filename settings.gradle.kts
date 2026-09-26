pluginManagement {
    includeBuild("build-logic")
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

include(":core")
include(":data")
include(":composeApp")
include(":androidApp")
include(":testing")

// Build-only module: custom detekt rules.
include(":detekt-rules")
project(":detekt-rules").projectDir = file("config/detekt-rules")

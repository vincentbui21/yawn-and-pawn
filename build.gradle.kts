import dev.detekt.gradle.extensions.DetektExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
    id("yawnandpawn.verify-core-dependencies")
}

// ---------------------------------------------------------------------------------------------
// Formatting: Spotless + ktlint over every Kotlin source and build script in the repo.
// ---------------------------------------------------------------------------------------------
spotless {
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    kotlin {
        target("**/src/**/*.kt")
        targetExclude("**/build/**")
        ktlint().setEditorConfigPath(rootProject.file(".editorconfig"))
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts", "config/*/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint().setEditorConfigPath(rootProject.file(".editorconfig"))
    }
}

// ---------------------------------------------------------------------------------------------
// Static analysis: detekt with config/detekt/detekt.yml and the custom rules in :detekt-rules.
// ---------------------------------------------------------------------------------------------
val detektConfig = files("config/detekt/detekt.yml")

detekt {
    // The root project only analyses the included build-logic sources.
    source.setFrom("build-logic/src")
    config.setFrom(detektConfig)
    buildUponDefaultConfig = true
}

subprojects {
    apply(plugin = "dev.detekt")
    extensions.configure<DetektExtension> {
        source.setFrom("src")
        config.setFrom(detektConfig)
        buildUponDefaultConfig = true
    }
    if (path != ":detekt-rules") {
        dependencies.add("detektPlugins", project(":detekt-rules"))
    }
}

// ---------------------------------------------------------------------------------------------
// Coverage: Kover aggregates :core; business logic needs at least 90% line coverage (NFR-11).
// ---------------------------------------------------------------------------------------------
dependencies {
    kover(project(":core"))
}

kover {
    reports {
        verify {
            rule("core line coverage") {
                minBound(90)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Definition of done (AD-14): `./gradlew qualityGate`.
//
// Later stories register their checks here as additional dependencies of `qualityGate`:
//   - Story 1.2: dependency allowlist check and manifest permission allowlist test
//   - Story 1.3: design token diff check (tools/tokens regenerates PpsTokens.kt, fails on diff)
//   - Story 1.17: sound loudness script (peak and integrated loudness of bundled sounds)
// Add them with `dependsOn(...)` below; never run a check outside the gate.
// ---------------------------------------------------------------------------------------------
tasks.register("qualityGate") {
    group = "verification"
    description = "Runs every check a story needs to be done (AD-14)."
    dependsOn(
        "spotlessCheck",
        "detekt",
        subprojects.map { "${it.path}:detekt" },
        ":core:allTests",
        ":testing:allTests",
        ":data:allTests",
        ":data:testAndroidHostTest",
        ":composeApp:allTests",
        ":composeApp:testAndroidHostTest",
        ":androidApp:testDebugUnitTest",
        ":detekt-rules:test",
        gradle.includedBuild("build-logic").task(":test"),
        "koverVerify",
        ":androidApp:lintDebug",
        ":androidApp:assembleDebug",
        "verifyCoreDependencies",
    )
}

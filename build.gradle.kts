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
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room3) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
    id("yawnandpawn.verify-core-dependencies")
    id("yawnandpawn.allowlists")
    id("yawnandpawn.sound-loudness")
    id("yawnandpawn.design-tokens")
}

// ---------------------------------------------------------------------------------------------
// Design tokens (AD-10, Story 1.3): tools/tokens generates PpsTokens.kt from the DESIGN.md
// frontmatter. `generateTokens` writes the committed file; `checkTokens` (qualityGate) fails on drift.
// ---------------------------------------------------------------------------------------------
designTokens {
    designFile.set(layout.projectDirectory.file("_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/DESIGN.md"))
    outputFile.set(layout.projectDirectory.file("composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/theme/PpsTokens.kt"))
    packageName.set("com.yawnandpawn.app.ui.theme")
}

// ---------------------------------------------------------------------------------------------
// Sound loudness (FR-SND-1, Story 1.17, plugin yawnandpawn.sound-loudness from build-logic):
// `checkSoundLoudness` (qualityGate) measures every bundled alarm sound with ffmpeg ebur128 and fails, naming
// the file, below a -3 dBFS sample peak or -14 LUFS integrated loudness. UI sounds are exempt and only listed.
// ffmpeg: Gradle property yawnandpawn.ffmpeg, or ffmpeg on PATH (CI installs it). Where ffmpeg cannot run,
// yawnandpawn.loudnessMeasurer=python measures with tools/sounds/measure_loudness.py through uv.
// ---------------------------------------------------------------------------------------------
soundLoudness {
    alarmSounds.from(fileTree("androidApp/src/main/res/raw") { include("alarm_*") })
    // The time-wheel tick (12 ms, about -12 dBFS) is a deliberately quiet UI sound.
    exemptSounds.from("composeApp/src/androidMain/res/raw/wheel_tick.wav")
    pythonScript.set(layout.projectDirectory.file("tools/sounds/measure_loudness.py"))
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
        target("*.gradle.kts", "*/*.gradle.kts", "config/*/*.gradle.kts", "tools/*/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint().setEditorConfigPath(rootProject.file(".editorconfig"))
    }
}

// ---------------------------------------------------------------------------------------------
// Static analysis: detekt with config/detekt/detekt.yml and the custom rules in :detekt-rules.
// ---------------------------------------------------------------------------------------------
val detektConfig = files("config/detekt/detekt.yml")

detekt {
    // The root project only analyses the included builds' sources (build-logic, tools/tokens).
    source.setFrom("build-logic/src", "tools/tokens/src")
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

// core.session has its own 90% rule (core/build.gradle.kts, variant "session"); it runs with this verify task.
tasks.named("koverVerify") {
    dependsOn(":core:koverVerifySession")
}

// ---------------------------------------------------------------------------------------------
// Definition of done (AD-14): `./gradlew qualityGate`.
//
// Allowlists (Story 1.2, plugin yawnandpawn.allowlists, tasks on :androidApp, debug + release):
//   - checkDependencyAllowlist: every resolved runtime group:artifact is in config/dependency-allowlist.txt
//   - checkPermissionAllowlist: merged manifests request only config/permission-allowlist.txt
//     permissions, SCHEDULE_EXACT_ALARM stops at API 32, no accessibility/device-admin/lock-task
// A story that adds a dependency or permission updates the allowlist file in the same change.
//
// Design tokens (Story 1.3, plugin yawnandpawn.design-tokens from tools/tokens):
//   - checkTokens: regenerates PpsTokens.kt into build/tokens and fails on any diff with the committed file
//   - the raw colour / radius / sp detekt rules, ContrastTest and CopyRulesTest run inside detekt and host tests
//
// Sound loudness (Story 1.17): checkSoundLoudness, see the soundLoudness block above.
//
// Later stories register their checks here as additional dependencies of `qualityGate`.
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
        gradle.includedBuild("tokens").task(":test"),
        "checkTokens",
        "koverVerify",
        ":androidApp:lintDebug",
        ":androidApp:assembleDebug",
        "verifyCoreDependencies",
        ":androidApp:checkDependencyAllowlist",
        ":androidApp:checkPermissionAllowlist",
        "checkSoundLoudness",
    )
}

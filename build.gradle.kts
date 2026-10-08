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
    // Story 1.19: applied by :androidApp only when androidApp/google-services.json exists.
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
    id("yawnandpawn.verify-core-dependencies")
    id("yawnandpawn.allowlists")
    id("yawnandpawn.sound-loudness")
    id("yawnandpawn.word-list")
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
// Word list (Story 3.7, plugin yawnandpawn.word-list from build-logic): `checkWordList` (qualityGate) fails unless the
// bundled Word Unscramble list is lowercase a-z, unique, 4-10 letters, off the blocklist, with 300 words per group.
// ---------------------------------------------------------------------------------------------
wordList {
    wordList.set(layout.projectDirectory.file("androidApp/src/main/assets/words_en.txt"))
    blocklist.set(layout.projectDirectory.file("config/word-blocklist.txt"))
}

// ---------------------------------------------------------------------------------------------
// Play catalogue (Story 4.1, AD-7, tools/play-catalog/README.md): `./gradlew playCatalog -PplayCatalogMode=dry-run|apply
// [-Pcredentials=<key file outside the repo>]` (or PLAY_SERVICE_ACCOUNT_JSON) creates and updates the 50 snooze
// products. The tool runs in its own JVM, so its Google API client never joins this build's classpath.
// ---------------------------------------------------------------------------------------------
val playCatalogTool =
    configurations.create("playCatalogTool") {
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
            attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
        }
    }

dependencies {
    // Substituted by the included build tools/play-catalog.
    playCatalogTool("com.yawnandpawn.tools:play-catalog")
}

tasks.register<JavaExec>("playCatalog") {
    group = "publishing"
    description = "Creates and updates the 50 snooze products in Play Console (-PplayCatalogMode=dry-run|apply; default dry-run)."
    classpath = playCatalogTool
    mainClass.set("com.yawnandpawn.app.playcatalog.MainKt")
    // The mode is read only from this command line (-PplayCatalogMode=...), never from gradle.properties or an
    // ORG_GRADLE_PROJECT_ variable, and defaults to dry-run: nothing but an explicit command writes to Play.
    val commandLine = gradle.startParameter.projectProperties
    val mode = commandLine["playCatalogMode"] ?: "dry-run"
    val legacyMode = commandLine.containsKey("mode")
    val credentials = providers.gradleProperty("credentials")
    val repoRoot = rootDir.absolutePath
    doFirst {
        if (legacyMode) throw GradleException("playCatalog takes -PplayCatalogMode=dry-run|apply, not -Pmode.")
    }
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf("--mode", mode, "--repo-root", repoRoot) +
                credentials.map { listOf("--credentials", it) }.getOrElse(emptyList())
        },
    )
    // Talks to Play every time; never up to date or cached.
    outputs.upToDateWhen { false }
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
    // The root project only analyses the included builds' sources (build-logic, tools/tokens, tools/play-catalog).
    source.setFrom("build-logic/src", "tools/tokens/src", "tools/play-catalog/src")
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

// core.session and core.checks have their own 90% rules (core/build.gradle.kts, variants "session" and "checks"); they
// run with this verify task.
tasks.named("koverVerify") {
    dependsOn(":core:koverVerifySession", ":core:koverVerifyChecks")
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
// Play catalogue (Story 4.1): the tools/play-catalog tests (fake API client, no network) and
// checkToolDependencyAllowlist (its runtime dependencies against config/tool-dependency-allowlist.txt).
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
        // Story 4.1: the Play catalogue tool's tests (fake API client only) and its tool-only dependency allowlist.
        gradle.includedBuild("play-catalog").task(":test"),
        gradle.includedBuild("play-catalog").task(":checkToolDependencyAllowlist"),
        "checkTokens",
        "koverVerify",
        ":androidApp:lintDebug",
        ":androidApp:assembleDebug",
        "verifyCoreDependencies",
        ":androidApp:checkDependencyAllowlist",
        ":androidApp:checkPermissionAllowlist",
        "checkSoundLoudness",
        // Story 3.7: the Word Unscramble list is clean (a-z, unique, 4-10 letters, not blocklisted, 300 per group).
        "checkWordList",
        // Story 1.18: no debug-only code (fire hook, design preview, theme showcase) in the release build.
        ":androidApp:checkReleaseContent",
    )
}

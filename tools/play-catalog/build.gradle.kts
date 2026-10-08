// tools/play-catalog: creates and updates the 50 snooze products in Play Console (Story 4.1, AD-7, AD-14).
// A top-level included build that the root `playCatalog` task runs in its own JVM, so the Play Developer API client
// stays off the Gradle build classpath and off every app classpath. See README.md.
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "com.yawnandpawn.tools"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.google.api.services.androidpublisher)
    implementation(libs.google.auth.library.oauth2.http)
    testImplementation(kotlin("test"))
}

val repoRoot: File =
    layout.projectDirectory.asFile
        .resolve("../..")
        .canonicalFile

tasks.test {
    useJUnit()
    // The shared id list, the package-id decision and the app's applicationId are compared with the tool's constants.
    systemProperty("yawnandpawn.repoRoot", repoRoot.path)
    inputs.file(repoRoot.resolve("config/snooze-products.txt")).withPropertyName("snoozeProducts")
    inputs.file(repoRoot.resolve("docs/decisions/package-id.md")).withPropertyName("packageIdDecision")
    inputs.file(repoRoot.resolve("androidApp/build.gradle.kts")).withPropertyName("appBuildScript")
}

// Every group:artifact on the tool's runtime classpath must be listed, with its licence, in
// config/tool-dependency-allowlist.txt (the tool's counterpart of the app's NFR-13 allowlist; part of qualityGate).
tasks.register("checkToolDependencyAllowlist") {
    group = "verification"
    description = "Fails on any runtime dependency of tools/play-catalog missing from config/tool-dependency-allowlist.txt."
    val allowlistFile = repoRoot.resolve("config/tool-dependency-allowlist.txt")
    val resolved =
        configurations.runtimeClasspath.map { classpath ->
            classpath.incoming.resolutionResult.allComponents
                .mapNotNull { it.id as? ModuleComponentIdentifier }
                .map { "${it.group}:${it.module}" }
                .toSortedSet()
                .toList()
        }
    inputs.file(allowlistFile).withPropertyName("allowlist")
    inputs.property("resolved", resolved)
    val marker = layout.buildDirectory.file("checkToolDependencyAllowlist/ok.txt")
    outputs.file(marker)
    doLast {
        val listed =
            allowlistFile
                .readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        val unlisted = resolved.get().filterNot { it in listed }
        if (unlisted.isNotEmpty()) {
            throw GradleException(
                "tools/play-catalog runtime dependencies missing from config/tool-dependency-allowlist.txt " +
                    "(add each with its licence after review): ${unlisted.joinToString()}",
            )
        }
        marker.get().asFile.writeText(resolved.get().joinToString("\n", postfix = "\n"))
    }
}

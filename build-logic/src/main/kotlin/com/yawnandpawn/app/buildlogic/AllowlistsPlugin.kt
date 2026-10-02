package com.yawnandpawn.app.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

const val CHECK_DEPENDENCY_ALLOWLIST = "checkDependencyAllowlist"
const val CHECK_PERMISSION_ALLOWLIST = "checkPermissionAllowlist"

/** The app variants both checks must see; a wiring regression that drops one fails the task. */
val APP_VARIANTS: Set<String> = setOf("debug", "release")

/**
 * Applied to the root project; registers `checkDependencyAllowlist` and `checkPermissionAllowlist`
 * on `:androidApp` (NFR-13, AD-5, AD-15). The tasks live in `:androidApp` because they resolve its
 * runtime classpaths and read its merged manifests; every AGP variant (debug, release) is checked.
 */
class AllowlistsPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        require(target == target.rootProject) { "Apply yawnandpawn.allowlists to the root project" }
        val app = requireNotNull(target.findProject(":androidApp")) { "yawnandpawn.allowlists needs an :androidApp project" }
        val config = target.layout.projectDirectory.dir("config")
        app.tasks.register(CHECK_DEPENDENCY_ALLOWLIST, CheckDependencyAllowlistTask::class.java) {
            group = "verification"
            description = "Fails if a resolved runtime dependency of the app is not in config/dependency-allowlist.txt."
            allowlist.set(config.file("dependency-allowlist.txt"))
            marker.set(app.layout.buildDirectory.file("allowlists/dependencies-ok.txt"))
        }
        app.tasks.register(CHECK_PERMISSION_ALLOWLIST, CheckPermissionAllowlistTask::class.java) {
            group = "verification"
            description = "Fails if a merged manifest requests an unlisted permission or a device-hostage component."
            allowlist.set(config.file("permission-allowlist.txt"))
            marker.set(app.layout.buildDirectory.file("allowlists/permissions-ok.txt"))
        }
        // Story 1.18: the release build ships no debug-only code (fire hook, design preview, theme showcase).
        app.tasks.register(CHECK_RELEASE_CONTENT, CheckReleaseContentTask::class.java) {
            group = "verification"
            description = "Fails if the release manifest, classes or resources contain debug-only content."
            marker.set(app.layout.buildDirectory.file("allowlists/release-content-ok.txt"))
        }
        // Isolated in AgpAllowlistWiring so AGP classes load only when AGP is applied.
        app.pluginManager.withPlugin("com.android.application") { AgpAllowlistWiring.wire(app) }
    }
}

/** `variant|group:artifact` for every external module on [configuration]'s resolved graph. */
fun runtimeCoordinates(
    variant: String,
    configuration: Configuration,
): Provider<List<String>> =
    configuration.incoming.resolutionResult.rootComponent.map { root ->
        moduleComponents(root).map { "$variant|${it.group}:${it.module}" }.sorted()
    }

private fun moduleComponents(root: ResolvedComponentResult): List<ModuleComponentIdentifier> {
    val seen = mutableSetOf<ResolvedComponentResult>()
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        if (seen.add(component)) {
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
        }
    }
    return seen.mapNotNull { it.id as? ModuleComponentIdentifier }.distinct()
}

abstract class CheckDependencyAllowlistTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val allowlist: RegularFileProperty

    /** `variant|group:artifact` entries resolved on the app's runtime classpaths. */
    @get:Input
    abstract val resolvedDependencies: ListProperty<String>

    /** Variants whose runtime classpath must have been gathered (default: debug and release). */
    @get:Input
    abstract val expectedVariants: SetProperty<String>

    @get:OutputFile
    abstract val marker: RegularFileProperty

    init {
        expectedVariants.convention(APP_VARIANTS)
    }

    @TaskAction
    fun check() {
        val resolved =
            resolvedDependencies.get().map {
                val (variant, coordinates) = it.split("|", limit = 2)
                ResolvedDependency(variant, coordinates)
            }
        requireVariants(CHECK_DEPENDENCY_ALLOWLIST, "runtime classpaths", expectedVariants.get(), resolved.map { it.variant })
        val text = allowlist.get().asFile.readText()
        val violations = DependencyAllowlist.malformedLines(text) + DependencyAllowlist.verify(DependencyAllowlist.parse(text), resolved)
        failOn(CHECK_DEPENDENCY_ALLOWLIST, "Review each dependency, then list it in config/dependency-allowlist.txt", violations)
        marker.get().asFile.writeText("ok\n")
    }
}

/** One variant's merged manifest, as a nested task input. */
abstract class ManifestInput {
    @get:Input
    abstract val variant: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val file: RegularFileProperty
}

abstract class CheckPermissionAllowlistTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val allowlist: RegularFileProperty

    @get:Nested
    abstract val manifests: ListProperty<ManifestInput>

    /** Variants whose merged manifest must have been gathered (default: debug and release). */
    @get:Input
    abstract val expectedVariants: SetProperty<String>

    @get:OutputFile
    abstract val marker: RegularFileProperty

    init {
        expectedVariants.convention(APP_VARIANTS)
    }

    @get:Inject
    abstract val objects: ObjectFactory

    /** Adds [variant]'s merged manifest to the check. */
    fun manifest(
        variant: String,
        file: Provider<RegularFile>,
    ) {
        manifests.add(
            objects.newInstance(ManifestInput::class.java).apply {
                this.variant.set(variant)
                this.file.set(file)
            },
        )
    }

    @TaskAction
    fun check() {
        val inputs =
            manifests.get().map {
                VariantManifest(
                    it.variant.get(),
                    it.file
                        .get()
                        .asFile
                        .readText(),
                )
            }
        requireVariants(CHECK_PERMISSION_ALLOWLIST, "merged manifests", expectedVariants.get(), inputs.map { it.variant })
        val allowlistPermissions = PermissionAllowlist.parse(allowlist.get().asFile.readText())
        val violations = PermissionAllowlist.verify(allowlistPermissions, inputs)
        failOn(CHECK_PERMISSION_ALLOWLIST, "Remove the permission or component, or review it and list it", violations)
        marker.get().asFile.writeText("ok\n")
    }
}

private fun requireVariants(
    task: String,
    what: String,
    expected: Set<String>,
    gathered: List<String>,
) {
    val actual = gathered.toSortedSet()
    if (actual != expected.toSortedSet()) {
        throw GradleException(
            "$task gathered $what for variants ${if (actual.isEmpty()) "(none)" else actual} but expected ${expected.toSortedSet()}; " +
                "the variant wiring is broken",
        )
    }
}

private fun failOn(
    task: String,
    advice: String,
    violations: List<String>,
) {
    if (violations.isNotEmpty()) {
        throw GradleException(
            "$task found ${violations.size} violation(s). $advice:\n" + violations.joinToString("\n") { "  - $it" },
        )
    }
}

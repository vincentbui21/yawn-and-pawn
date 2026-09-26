package com.yawnandpawn.app.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Configurations that declare dependencies: `api`, `commonMainImplementation`, `testImplementation`, ...
 * plus annotation processors (`kspJvm`, `kapt`, `annotationProcessor`).
 */
private val declaringConfiguration =
    Regex(
        """^(\w+(Api|Implementation|CompileOnly|RuntimeOnly)|api|implementation|compileOnly|runtimeOnly""" +
            """|ksp\w*|kapt\w*|annotationProcessor)$""",
    )

/** Applied to the root project; registers `verifyCoreDependencies` (AD-1). */
class VerifyCoreDependenciesPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        require(target == target.rootProject) { "Apply yawnandpawn.verify-core-dependencies to the root project" }
        val core = target.project(":core")
        val task =
            target.tasks.register("verifyCoreDependencies", VerifyCoreDependenciesTask::class.java) {
                group = "verification"
                description = "Fails if :core is not platform-free or the module graph differs from the architecture (AD-1)."
                coreProjectDir.set(core.layout.projectDirectory)
                coreSources.from(core.fileTree("src") { include("**/*.kt", "**/*.kts", "**/*.java") })
                marker.set(target.layout.buildDirectory.file("verifyCoreDependencies/ok.txt"))
            }
        target.gradle.projectsEvaluated {
            val coreDependencies =
                core.configurations
                    .filter { declaringConfiguration.matches(it.name) }
                    .flatMap { configuration ->
                        configuration.dependencies.filterNot { it is FileCollectionDependency }.map { dependency ->
                            val coordinates =
                                if (dependency is ProjectDependency) {
                                    "project ${dependency.path}"
                                } else {
                                    "${dependency.group}:${dependency.name}"
                                }
                            "${configuration.name}|$coordinates"
                        }
                    }
            val edges =
                CoreDependencyRules.allowedEdges.keys.flatMap { module ->
                    target
                        .findProject(":$module")
                        ?.configurations
                        .orEmpty()
                        .filter { declaringConfiguration.matches(it.name) }
                        .flatMap { configuration ->
                            val testOnly = CoreDependencyRules.isTestConfiguration(configuration.name)
                            configuration.dependencies.withType(ProjectDependency::class.java).map { dependency ->
                                "$module|${dependency.path.removePrefix(":")}|$testOnly"
                            }
                        }
                }
            val projects = target.subprojects.map { it.path.removePrefix(":") }
            task.configure {
                this.projects.set(projects.sorted())
                this.coreDependencies.set(coreDependencies.distinct().sorted())
                this.edges.set(edges.distinct().sorted())
            }
        }
    }
}

abstract class VerifyCoreDependenciesTask : DefaultTask() {
    /** `configuration|group:name` entries declared by `:core`. */
    @get:Input
    abstract val coreDependencies: ListProperty<String>

    /** `from|to|testOnly` module edges of the architecture modules. */
    @get:Input
    abstract val edges: ListProperty<String>

    /** Names of all included projects except the root. */
    @get:Input
    abstract val projects: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val coreSources: ConfigurableFileCollection

    @get:Internal
    abstract val coreProjectDir: DirectoryProperty

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun verify() {
        val root = coreProjectDir.get().asFile
        val input =
            CoreCheckInput(
                coreDependencies =
                    coreDependencies.get().map {
                        val (configuration, coordinates) = it.split("|", limit = 2)
                        DeclaredDependency(configuration, coordinates)
                    },
                coreSources =
                    coreSources.files.sorted().map {
                        SourceFile(it.relativeTo(root).invariantSeparatorsPath, it.readText())
                    },
                edges =
                    edges.get().map {
                        val (from, to, testOnly) = it.split("|")
                        ProjectEdge(from, to, testOnly.toBoolean())
                    },
                projects = projects.get(),
            )
        val violations = CoreDependencyRules.verify(input)
        if (violations.isNotEmpty()) {
            throw GradleException(
                "verifyCoreDependencies found ${violations.size} violation(s) of AD-1:\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
        marker.get().asFile.writeText("ok\n")
    }
}

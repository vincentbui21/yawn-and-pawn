package com.yawnandpawn.app.buildlogic

/** A dependency declared by `:core`: `group:name`, or `project :path` for a module dependency. */
data class DeclaredDependency(
    val configuration: String,
    val coordinates: String,
)

/** A `:core` source file, path relative to the `:core` project directory (`src/commonMain/...`). */
data class SourceFile(
    val path: String,
    val text: String,
)

/** A module-to-module dependency, e.g. `composeApp -> core`. */
data class ProjectEdge(
    val from: String,
    val to: String,
    val testOnly: Boolean,
) {
    override fun toString(): String = "$from -> $to" + if (testOnly) " (test)" else ""
}

data class CoreCheckInput(
    val coreDependencies: List<DeclaredDependency>,
    val coreSources: List<SourceFile>,
    val edges: List<ProjectEdge>,
    /** Names of all included projects except the root, e.g. `core`, `detekt-rules`. */
    val projects: List<String> = emptyList(),
)

/**
 * AD-1 rules: `:core` is platform-free and the module graph is exactly the architecture graph.
 * Pure function over [CoreCheckInput]; the Gradle task only gathers the input and fails on violations.
 */
object CoreDependencyRules {
    /** Exact `group:name` artifacts `:core` may declare. */
    val allowedCoreArtifacts: Set<String> =
        setOf(
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core",
            "org.jetbrains.kotlinx:kotlinx-datetime",
            "org.jetbrains.kotlinx:kotlinx-serialization-core",
            "org.jetbrains.kotlinx:kotlinx-serialization-json",
        )

    /** Extra exact `group:name` artifacts allowed only in `:core` test configurations. */
    val allowedCoreTestArtifacts: Set<String> =
        setOf(
            "org.jetbrains.kotlin:kotlin-test",
            "org.jetbrains.kotlinx:kotlinx-coroutines-test",
        )

    /** Import prefixes that must never appear in `:core` sources. */
    val forbiddenImportPrefixes: List<String> =
        listOf(
            "android.",
            "androidx.",
            "org.koin.",
            "org.jetbrains.compose.",
        )

    /** Source sets `:core` may contain. */
    val allowedCoreSourceSets: Set<String> = setOf("commonMain", "commonTest")

    /** Allowed non-test edges: the architecture dependency graph. */
    val allowedEdges: Map<String, Set<String>> =
        mapOf(
            "androidApp" to setOf("composeApp", "data", "core"),
            "composeApp" to setOf("core"),
            "data" to setOf("core"),
            "testing" to setOf("core"),
            "core" to emptySet(),
        )

    /** Build-only projects allowed besides the architecture modules. */
    val buildOnlyProjects: Set<String> = setOf("detekt-rules")

    /** Modules whose tests may additionally depend on `:testing`. */
    val testingConsumers: Set<String> = setOf("androidApp", "composeApp", "data")

    private val importRegex = Regex("""^\s*import\s+([\w.`*]+)""", RegexOption.MULTILINE)
    private val sourceSetRegex = Regex("""^src/([^/]+)/""")

    fun verify(input: CoreCheckInput): List<String> =
        dependencyViolations(input.coreDependencies) +
            importViolations(input.coreSources) +
            sourceSetViolations(input.coreSources) +
            edgeViolations(input.edges) +
            projectViolations(input.projects)

    fun isTestConfiguration(name: String): Boolean = name.startsWith("test") || name.contains("Test")

    private fun dependencyViolations(dependencies: List<DeclaredDependency>): List<String> =
        dependencies
            .filterNot { dependency ->
                val allowed =
                    if (isTestConfiguration(dependency.configuration)) {
                        allowedCoreArtifacts + allowedCoreTestArtifacts
                    } else {
                        allowedCoreArtifacts
                    }
                dependency.coordinates in allowed
            }.map { ":core declares forbidden dependency '${it.coordinates}' in configuration '${it.configuration}'" }

    private fun importViolations(sources: List<SourceFile>): List<String> =
        sources.flatMap { source ->
            importRegex
                .findAll(source.text)
                .map { it.groupValues[1].replace("`", "") }
                .filter { import -> forbiddenImportPrefixes.any { import.startsWith(it) } }
                .map { import -> ":core source '${source.path}' has forbidden import '$import'" }
                .toList()
        }

    private fun sourceSetViolations(sources: List<SourceFile>): List<String> =
        sources
            .mapNotNull { sourceSetRegex.find(it.path.replace('\\', '/'))?.groupValues?.get(1) }
            .distinct()
            .filterNot { it in allowedCoreSourceSets }
            .map { ":core has forbidden source set '$it' (only commonMain and commonTest are allowed)" }

    private fun edgeViolations(edges: List<ProjectEdge>): List<String> =
        edges
            .distinct()
            .filterNot { edge -> edge.from == edge.to }
            .filterNot { edge ->
                val allowed = allowedEdges[edge.from].orEmpty()
                edge.to in allowed || (edge.testOnly && edge.from in testingConsumers && edge.to == "testing")
            }.map { edge -> "Forbidden module dependency '$edge'" }

    private fun projectViolations(projects: List<String>): List<String> =
        projects
            .distinct()
            .filterNot { it in allowedEdges.keys || it in buildOnlyProjects }
            .map { "Unknown module '$it': only the architecture modules and detekt-rules may be included" }
}

package com.yawnandpawn.app.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreDependencyRulesTest {
    private val architectureEdges =
        listOf(
            ProjectEdge("androidApp", "composeApp", testOnly = false),
            ProjectEdge("androidApp", "data", testOnly = false),
            ProjectEdge("androidApp", "core", testOnly = false),
            ProjectEdge("composeApp", "core", testOnly = false),
            ProjectEdge("data", "core", testOnly = false),
            ProjectEdge("testing", "core", testOnly = false),
            ProjectEdge("androidApp", "testing", testOnly = true),
            ProjectEdge("composeApp", "testing", testOnly = true),
            ProjectEdge("data", "testing", testOnly = true),
        )

    private val passingFixture =
        CoreCheckInput(
            coreDependencies =
                listOf(
                    DeclaredDependency("commonMainImplementation", "org.jetbrains.kotlin:kotlin-stdlib"),
                    DeclaredDependency("commonMainApi", "org.jetbrains.kotlinx:kotlinx-coroutines-core"),
                    DeclaredDependency("commonMainImplementation", "org.jetbrains.kotlinx:kotlinx-datetime"),
                    DeclaredDependency("commonMainImplementation", "org.jetbrains.kotlinx:kotlinx-serialization-json"),
                    DeclaredDependency("commonTestImplementation", "org.jetbrains.kotlin:kotlin-test"),
                ),
            coreSources =
                listOf(
                    SourceFile(
                        "src/commonMain/kotlin/com/yawnandpawn/app/core/AppVersion.kt",
                        "package com.yawnandpawn.app.core\n\nimport kotlinx.datetime.LocalTime\nimport kotlin.time.Duration\n",
                    ),
                    SourceFile(
                        "src/commonTest/kotlin/com/yawnandpawn/app/core/AppVersionTest.kt",
                        "package com.yawnandpawn.app.core\n\nimport kotlin.test.Test\n",
                    ),
                ),
            edges = architectureEdges,
            projects = listOf("androidApp", "composeApp", "core", "data", "detekt-rules", "testing"),
        )

    @Test
    fun `the passing fixture has no violations`() {
        assertEquals(emptyList(), CoreDependencyRules.verify(passingFixture))
    }

    @Test
    fun `a forbidden core dependency is reported with its coordinates`() {
        val input =
            passingFixture.copy(
                coreDependencies =
                    passingFixture.coreDependencies +
                        DeclaredDependency("commonMainImplementation", "io.insert-koin:koin-core") +
                        DeclaredDependency("commonMainImplementation", "androidx.room3:room3-runtime"),
            )

        val violations = CoreDependencyRules.verify(input)

        assertEquals(2, violations.size)
        assertTrue(violations[0].contains("io.insert-koin:koin-core"))
        assertTrue(violations[1].contains("androidx.room3:room3-runtime"))
    }

    @Test
    fun `kotlin-test is allowed only in test configurations`() {
        val input =
            passingFixture.copy(
                coreDependencies = listOf(DeclaredDependency("commonMainImplementation", "org.jetbrains.kotlin:kotlin-test")),
            )

        val violations = CoreDependencyRules.verify(input)

        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("org.jetbrains.kotlin:kotlin-test"))
    }

    @Test
    fun `a module dependency declared by core is reported`() {
        val input =
            passingFixture.copy(
                coreDependencies = listOf(DeclaredDependency("commonMainImplementation", "project :data")),
            )

        assertTrue(CoreDependencyRules.verify(input).single().contains("project :data"))
    }

    @Test
    fun `forbidden imports in core are reported with file and import`() {
        val source =
            SourceFile(
                "src/commonMain/kotlin/com/yawnandpawn/app/core/Bad.kt",
                """
                package com.yawnandpawn.app.core

                import android.content.Context
                import androidx.compose.runtime.Composable
                import org.koin.core.module.Module
                import org.jetbrains.compose.resources.stringResource
                import androidx.room3.Dao
                import kotlinx.coroutines.flow.Flow
                """.trimIndent(),
            )
        val input = passingFixture.copy(coreSources = passingFixture.coreSources + source)

        val violations = CoreDependencyRules.verify(input)

        assertEquals(5, violations.size)
        violations.forEach { assertTrue(it.contains("src/commonMain/kotlin/com/yawnandpawn/app/core/Bad.kt")) }
        assertTrue(violations.any { it.contains("'android.content.Context'") })
        assertTrue(violations.any { it.contains("'androidx.compose.runtime.Composable'") })
        assertTrue(violations.any { it.contains("'org.koin.core.module.Module'") })
        assertTrue(violations.any { it.contains("'org.jetbrains.compose.resources.stringResource'") })
        assertTrue(violations.any { it.contains("'androidx.room3.Dao'") })
    }

    @Test
    fun `a core source set other than commonMain or commonTest is reported`() {
        val source = SourceFile("src/androidMain/kotlin/com/yawnandpawn/app/core/Platform.kt", "package com.yawnandpawn.app.core\n")
        val input = passingFixture.copy(coreSources = passingFixture.coreSources + source)

        assertTrue(CoreDependencyRules.verify(input).single().contains("'androidMain'"))
    }

    @Test
    fun `composeApp depending on data is reported naming the edge`() {
        val input = passingFixture.copy(edges = architectureEdges + ProjectEdge("composeApp", "data", testOnly = false))

        val violations = CoreDependencyRules.verify(input)

        assertEquals(listOf("Forbidden module dependency 'composeApp -> data'"), violations)
    }

    @Test
    fun `testing is allowed only as a test dependency of its consumers`() {
        val input =
            passingFixture.copy(
                edges =
                    architectureEdges +
                        ProjectEdge("data", "testing", testOnly = false) +
                        ProjectEdge("core", "testing", testOnly = true),
            )

        val violations = CoreDependencyRules.verify(input)

        assertEquals(
            listOf(
                "Forbidden module dependency 'data -> testing'",
                "Forbidden module dependency 'core -> testing (test)'",
            ),
            violations,
        )
    }

    @Test
    fun `a platform coroutines artifact in core is reported`() {
        val input =
            passingFixture.copy(
                coreDependencies =
                    passingFixture.coreDependencies +
                        DeclaredDependency("commonMainImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-android"),
            )

        assertTrue(CoreDependencyRules.verify(input).single().contains("org.jetbrains.kotlinx:kotlinx-coroutines-android"))
    }

    @Test
    fun `kotlinx-coroutines-test in a main configuration is reported`() {
        val input =
            passingFixture.copy(
                coreDependencies =
                    passingFixture.coreDependencies +
                        DeclaredDependency("commonMainImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-test") +
                        DeclaredDependency("commonTestImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-test"),
            )

        val violations = CoreDependencyRules.verify(input)

        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("in configuration 'commonMainImplementation'"))
    }

    @Test
    fun `an included project outside the architecture is reported`() {
        val input =
            passingFixture.copy(
                projects = listOf("androidApp", "composeApp", "core", "data", "detekt-rules", "testing", "feature"),
            )

        assertEquals(
            listOf("Unknown module 'feature': only the architecture modules and detekt-rules may be included"),
            CoreDependencyRules.verify(input),
        )
    }

    @Test
    fun `test configurations are recognised by name`() {
        assertTrue(CoreDependencyRules.isTestConfiguration("testImplementation"))
        assertTrue(CoreDependencyRules.isTestConfiguration("commonTestImplementation"))
        assertTrue(CoreDependencyRules.isTestConfiguration("androidHostTestImplementation"))
        assertTrue(!CoreDependencyRules.isTestConfiguration("commonMainImplementation"))
        assertTrue(!CoreDependencyRules.isTestConfiguration("implementation"))
    }
}

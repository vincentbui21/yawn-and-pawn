package com.yawnandpawn.app.buildlogic

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.Project

/**
 * Feeds every AGP variant's runtime classpath and merged manifest into the allowlist checks, and the release variant's
 * manifest, classes and resources into the release-content check (Story 1.18).
 */
internal object AgpAllowlistWiring {
    fun wire(app: Project) {
        val components = app.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        components.onVariants { variant ->
            val coordinates = runtimeCoordinates(variant.name, variant.runtimeConfiguration)
            val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
            app.tasks.named(CHECK_DEPENDENCY_ALLOWLIST, CheckDependencyAllowlistTask::class.java).configure {
                resolvedDependencies.addAll(coordinates)
            }
            app.tasks.named(CHECK_PERMISSION_ALLOWLIST, CheckPermissionAllowlistTask::class.java).configure {
                manifest(variant.name, manifest)
            }
            if (variant.buildType == RELEASE) {
                val check = app.tasks.named(CHECK_RELEASE_CONTENT, CheckReleaseContentTask::class.java)
                variant.artifacts
                    .forScope(ScopedArtifacts.Scope.PROJECT)
                    .use(check)
                    .toGet(ScopedArtifact.CLASSES, CheckReleaseContentTask::classJars, CheckReleaseContentTask::classDirs)
                check.configure {
                    this.manifest.set(manifest)
                    variant.sources.res
                        ?.all
                        ?.let { resourceDirs.from(it) }
                }
            }
        }
    }

    private const val RELEASE = "release"
}

package com.yawnandpawn.app.buildlogic

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Project

/** Feeds every AGP variant's runtime classpath and merged manifest into the allowlist checks. */
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
        }
    }
}

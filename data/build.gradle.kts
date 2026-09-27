// :data — data adapters (Room KMP, DataStore, repositories) for core ports. Only :data touches databases.
// app.db (Room 3, docs/decisions/oq-2-room.md) lives in device-protected storage; exported schemas go to data/schemas/.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

kotlin {
    jvmToolchain(
        libs.versions.jvm.toolchain
            .get()
            .toInt(),
    )

    android {
        namespace = "com.yawnandpawn.app.data"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.koin.core)
            implementation(libs.room3.runtime)
        }
        androidMain.dependencies {
            implementation(libs.sqlite.framework)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit4)
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
        }
    }
}

dependencies {
    add("kspAndroid", libs.room3.compiler)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

// AGP's lint tasks read the KSP-generated source folders without a task dependency, which Gradle rejects as an
// implicit dependency when both run in one build (qualityGate). Declare it explicitly.
tasks.configureEach {
    if (name.endsWith("LintModel") || name.startsWith("lintAnalyze")) {
        dependsOn(tasks.matching { it.name.startsWith("kspAndroid") })
    }
}

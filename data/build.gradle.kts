// :data — data adapters (Room KMP, DataStore, repositories) for core ports. Only :data touches databases.
// Room and DataStore arrive in later stories; exported Room schemas go to data/schemas/.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
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
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(libs.kotlin.test)
        }
    }
}

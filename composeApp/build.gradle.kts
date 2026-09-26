// :composeApp — UI (Compose Multiplatform): theme, screens, ViewModels, navigation.
// Depends on :core only; never on :data (AD-1, enforced by verifyCoreDependencies).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(
        libs.versions.jvm.toolchain
            .get()
            .toInt(),
    )

    android {
        namespace = "com.yawnandpawn.app.ui"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        androidResources {
            enable = true
        }
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(libs.kotlin.test)
        }
    }
}

compose.resources {
    packageOfResClass = "com.yawnandpawn.app.ui.resources"
    publicResClass = true
}

// Host tests read DESIGN.md (ContrastTest) and the string/font resources (CopyRulesTest, GeistFontTest).
// Same DESIGN.md as the designTokens block in the root build file.
val designMd = rootProject.layout.projectDirectory.file("_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/DESIGN.md")
val composeResourcesDir = layout.projectDirectory.dir("src/commonMain/composeResources")
tasks.withType<Test>().configureEach {
    inputs.file(designMd).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(composeResourcesDir).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("yawnandpawn.designMd", designMd.asFile.absolutePath)
    systemProperty("yawnandpawn.composeResources", composeResourcesDir.asFile.absolutePath)
    // CopyRulesTest also scans the launcher strings of :androidApp (read as files; no project dependency).
    val androidRes = rootProject.layout.projectDirectory.dir("androidApp/src/main/res")
    inputs.dir(androidRes).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("yawnandpawn.androidRes", androidRes.asFile.absolutePath)
}

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(
        libs.versions.jvm.toolchain
            .get()
            .toInt(),
    )
}

dependencies {
    compileOnly(libs.detekt.api)

    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.test) {
        // detekt-test 2.0.0-alpha.5 requests the unpublished detekt-api test fixtures; detekt-api is added above.
        exclude(group = "dev.detekt", module = "detekt-api")
    }
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit4)
    // DetektConfigTest reads config/detekt/detekt.yml.
    testImplementation(libs.snakeyaml)
}

tasks.test {
    useJUnit()
    val detektConfig = rootProject.layout.projectDirectory.file("config/detekt/detekt.yml")
    inputs.file(detektConfig).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("yawnandpawn.detektConfig", detektConfig.asFile.absolutePath)
}

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
}

tasks.test {
    useJUnit()
}

plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // AGP variant API for the merged-manifest artifact and runtime classpaths. compileOnly: the
    // main build applies AGP itself; the allowlist plugin only touches it when AGP is present.
    compileOnly(libs.android.gradle.api)
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

tasks.test {
    useJUnit()
}

gradlePlugin {
    plugins {
        register("verifyCoreDependencies") {
            id = "yawnandpawn.verify-core-dependencies"
            implementationClass = "com.yawnandpawn.app.buildlogic.VerifyCoreDependenciesPlugin"
        }
        register("allowlists") {
            id = "yawnandpawn.allowlists"
            implementationClass = "com.yawnandpawn.app.buildlogic.AllowlistsPlugin"
        }
    }
}

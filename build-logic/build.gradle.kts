plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
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
    }
}

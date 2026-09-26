// tools/tokens: DESIGN.md frontmatter -> PpsTokens.kt generator (AD-10, UX-DR1).
// An included build (not a subproject), so the YAML parser stays a build-time dependency only.
plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.snakeyaml)
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

tasks.test {
    useJUnit()
}

gradlePlugin {
    plugins {
        register("designTokens") {
            id = "yawnandpawn.design-tokens"
            implementationClass = "com.yawnandpawn.app.tokens.DesignTokensPlugin"
        }
    }
}

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
    // The loudness fixture test measures real files with the same tool as checkSoundLoudness (Story 1.17): ffmpeg by
    // default (CI), or the python measurer where ffmpeg cannot run (set in ~/.gradle/gradle.properties).
    systemProperty("yawnandpawn.loudnessMeasurer", providers.gradleProperty("yawnandpawn.loudnessMeasurer").getOrElse("ffmpeg"))
    systemProperty("yawnandpawn.ffmpeg", providers.gradleProperty("yawnandpawn.ffmpeg").getOrElse("ffmpeg"))
    systemProperty("yawnandpawn.uv", providers.gradleProperty("yawnandpawn.uv").getOrElse("uv"))
    val measureScript = layout.projectDirectory.file("../tools/sounds/measure_loudness.py")
    inputs.files(measureScript, layout.projectDirectory.file("../tools/sounds/loudness.py")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("yawnandpawn.measureScript", measureScript.asFile.absolutePath)
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
        register("soundLoudness") {
            id = "yawnandpawn.sound-loudness"
            implementationClass = "com.yawnandpawn.app.buildlogic.SoundLoudnessPlugin"
        }
    }
}

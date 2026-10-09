// :testing — fakes for every core port, fixed clocks and builders. jvm() target only;
// Android host tests consume it through Kotlin's jvm -> androidJvm compatibility.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(
        libs.versions.jvm.toolchain
            .get()
            .toInt(),
    )
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Story 4.2: SnoozeProductsFileTest (jvmTest here, as :core allows only common source sets) checks the fee ladder
// against the product list tools/play-catalog reads.
val snoozeProducts = rootProject.layout.projectDirectory.file("config/snooze-products.txt")

// Story 4.13: TaxExclusiveCountriesFileTest checks TaxNote against the shared country list.
val taxExclusiveCountries = rootProject.layout.projectDirectory.file("config/tax-exclusive-countries.txt")
tasks.withType<Test>().configureEach {
    inputs.file(snoozeProducts).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("yawnandpawn.snoozeProducts", snoozeProducts.asFile.absolutePath)
    inputs.file(taxExclusiveCountries).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("yawnandpawn.taxExclusiveCountries", taxExclusiveCountries.asFile.absolutePath)
}

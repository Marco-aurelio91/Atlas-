plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":athena:core"))
    implementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":athena:core")))
    testImplementation(libs.kotlinx.coroutines.test)
}

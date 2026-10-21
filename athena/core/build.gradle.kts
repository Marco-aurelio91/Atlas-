plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
    `java-test-fixtures`
}

kotlin { jvmToolchain(21) }

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testFixturesImplementation(libs.junit)
    testFixturesImplementation(libs.kotlinx.coroutines.test)
}

tasks.test { systemProperty("athena.repositoryRoot", rootProject.projectDir.absolutePath) }

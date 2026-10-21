plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":athena:core"))
    testImplementation(project(":athena:runtime"))
    testImplementation(project(":athena:connector-weather"))
    testImplementation(project(":athena:connector-status"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Explicit opt-in network probe. Never part of deterministic tests/CI.
tasks.register<JavaExec>("liveAthenaProbe") {
    group = "verification"
    description = "Queries public weather at a fixed test region and public GitHub status through ATHENA."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.unyx.atlas.athena.LiveAthenaProbeKt")
}

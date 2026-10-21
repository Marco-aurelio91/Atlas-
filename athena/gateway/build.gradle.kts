plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }

application {
    mainClass.set("com.unyx.athena.gateway.ApplicationKt")
}

dependencies {
    // Reuses the already-tested domain engine — this worker translates HTTP onto WorldRuntime,
    // it doesn't reimplement query/cache/health/circuit-breaking.
    implementation(project(":athena:core"))
    implementation(project(":athena:runtime"))
    implementation(project(":athena:connector-weather"))
    implementation(project(":athena:connector-status"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.logback.classic)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
}

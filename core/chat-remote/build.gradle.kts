plugins {
    id("atlas.android.library")
    id("atlas.android.hilt")
}

android {
    namespace = "com.unyx.atlas.chatremote"
}

dependencies {
    implementation(project(":core:chat-crypto"))
    implementation(project(":core:chat-storage"))
    implementation(project(":core:chat-pairing"))
    implementation(project(":core:memory"))
    implementation(libs.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Same reason as the other chat-* modules: these tests exercise real ChatSession/X25519 key
    // agreement on the host JVM, not just this module's own orchestration logic.
    testImplementation("org.conscrypt:conscrypt-openjdk-uber:2.7.0")
}

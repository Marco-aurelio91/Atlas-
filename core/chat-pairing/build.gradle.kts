plugins {
    id("atlas.android.library")
}

android {
    namespace = "com.unyx.atlas.chatpairing"
}

dependencies {
    implementation(project(":core:chat-crypto"))
    implementation(project(":core:chat-storage"))
    implementation(libs.json)

    testImplementation(libs.junit)
    // Same reason as the other chat-* modules: these tests exercise real X25519 agreement
    // (ChatSession.establish) on the host JVM, not just this module's own parsing/validation logic.
    testImplementation("org.conscrypt:conscrypt-openjdk-uber:2.7.0")
}

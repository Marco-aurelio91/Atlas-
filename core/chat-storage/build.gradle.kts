plugins {
    id("atlas.android.library")
    id("atlas.android.hilt")
}

android {
    namespace = "com.unyx.atlas.chatstorage"
}

dependencies {
    implementation(project(":core:chat-crypto"))
    implementation(project(":core:security"))
    implementation(libs.json)
    // ChatAvatarStore's own EncryptedFile usage — core/security depends on this too, but only as
    // `implementation`, so it isn't visible here transitively without declaring it directly.
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    // Same reason as core/chat-crypto's own build.gradle.kts: these tests exercise real
    // ChatSession/X25519 key agreement on the host JVM, not just this module's own storage logic.
    testImplementation("org.conscrypt:conscrypt-openjdk-uber:2.7.0")
}

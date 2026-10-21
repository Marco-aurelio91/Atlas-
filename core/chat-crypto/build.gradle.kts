plugins {
    id("atlas.android.library")
}

android {
    namespace = "com.unyx.atlas.chatcrypto"
}

dependencies {
    // Runtime: installs X25519 ("XDH") support on API 30 devices, where the platform's own
    // provider doesn't guarantee it (only API 31+ does). See libs.versions.toml's comment and
    // docs/ATLAS_CHAT_DESENHO_CRIPTOGRAFICO.md §2/§7.
    implementation(libs.conscrypt.android)

    testImplementation(libs.junit)
    // Desktop-JVM build of the same Conscrypt release, native libs for host platforms bundled —
    // conscrypt-android's own native libs are Android-ABI-only and unusable from a JVM unit test
    // running on the build machine. Test-only; never shipped in the app.
    testImplementation("org.conscrypt:conscrypt-openjdk-uber:2.7.0")
}

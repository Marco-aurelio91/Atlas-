plugins {
    id("atlas.android.library")
}

android {
    namespace = "com.unyx.atlas.security"
}

dependencies {
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}

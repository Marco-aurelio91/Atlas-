plugins {
    id("atlas.android.library")
    id("atlas.android.compose")
}

android {
    namespace = "com.unyx.atlas.ui"
}

dependencies {
    testImplementation(libs.junit)
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.coil.compose)
}

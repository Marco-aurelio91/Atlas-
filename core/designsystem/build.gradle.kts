plugins {
    id("atlas.android.library")
    id("atlas.android.compose")
}

android {
    namespace = "com.unyx.atlas.designsystem"
}

dependencies {
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
}

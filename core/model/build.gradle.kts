plugins {
    id("atlas.android.library")
}

android {
    namespace = "com.unyx.atlas.model"
}

dependencies {
    // Occurrence/OccurrenceRegistry: a shared, plugin-agnostic model for "something in progress"
    // (download, media, low battery...) — feature:home's battery widget renders whatever's
    // published here without depending on whoever publishes it.
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

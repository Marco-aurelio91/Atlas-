plugins {
    id("atlas.android.feature")
}

android {
    namespace = "com.unyx.atlas.feature.settings"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:memory"))
    implementation(project(":core:security"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":feature:updater"))
}

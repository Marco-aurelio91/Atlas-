plugins {
    id("atlas.android.feature")
    id("atlas.android.hilt")
}

android {
    namespace = "com.unyx.atlas.feature.notes"
}

dependencies {
    implementation(project(":core:memory"))
    implementation(project(":core:security"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:model"))

    implementation(libs.json)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}

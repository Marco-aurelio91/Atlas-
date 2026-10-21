plugins {
    id("atlas.android.feature")
    // Navigation 3's NavKey destinations need kotlinx.serialization (@Serializable) for
    // rememberNavBackStack to survive process death — see AtlasConnectScreen's own doc.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.unyx.atlas.feature.connect"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:chat-crypto"))
    implementation(project(":core:chat-storage"))
    implementation(project(":core:chat-pairing"))
    implementation(project(":core:chat-remote"))
    implementation(project(":core:memory"))

    implementation(libs.coil.compose)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)

    // ChatNotificationWorker (plan §15) — same trio feature:updater's own UpdateWorker uses.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Navigation 3 — AtlasConnectScreen's own back stack (2026-10-17 decision: see its doc).
    // First real use of this dependency anywhere in the project; it was catalogued but unused.
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.kotlinx.serialization.json)

    // First test module in feature:connect — NfcCardTransferTest exercises pure APDU chunking
    // logic with zero Android dependency, same as this app's other protocol-level tests.
    testImplementation(libs.junit)
}

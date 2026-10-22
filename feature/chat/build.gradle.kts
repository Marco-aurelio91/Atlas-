plugins {
    id("atlas.android.feature")
    id("atlas.android.hilt")
}

// ClaudeClient lives in core:data, so any feature can use the authenticated Supabase AI gateway
// without depending on feature:chat — see core/data/build.gradle.kts.

android {
    namespace = "com.unyx.atlas.feature.chat"
}

dependencies {
    implementation(project(":core:memory"))
    implementation(project(":core:security"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:model"))

    implementation(libs.okhttp)
    implementation(libs.json)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.activity.compose)

    // Google Sign-In via Android Credential Manager, exchanged for a Supabase session
    // (AuthRepository.signInWithGoogle) — the CredentialManager call itself needs an Activity
    // context to show the account picker, so it lives here rather than in core:memory.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.mlkit.text.recognition)

    // WorkManager (already used by feature:updater/feature:backup) — the daily-time trigger for
    // Rotinas Visuais (local automations) reuses the same scheduler pattern.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.play.services.location)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.google.truth)
}

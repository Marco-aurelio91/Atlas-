import java.io.FileInputStream
import java.util.Properties

plugins {
    id("atlas.android.library")
    id("atlas.android.hilt")
}

// Only the public Supabase project URL and publishable/anon key are included in the APK. Provider
// credentials are configured as Supabase Edge Function secrets and never compiled into the app.
val supabasePropsFile = rootProject.file("supabase.properties")
val supabaseProps = Properties().apply {
    if (supabasePropsFile.exists()) FileInputStream(supabasePropsFile).use { load(it) }
}

android {
    namespace = "com.unyx.atlas.data"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "SUPABASE_URL", "\"${supabaseProps.getProperty("SUPABASE_URL", "")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${supabaseProps.getProperty("SUPABASE_ANON_KEY", "")}\"")
    }
}

dependencies {
    // api, not implementation: AthenaCoordinator's public surface exposes WorldRuntime/
    // com.unyx.athena.core types directly (see AthenaCoordinator.kt), and feature:settings — a
    // consumer of core:data, not a direct dependency of these athena modules — reads them straight
    // off the coordinator to render its own settings section. implementation would hide them.
    api(project(":core:athena-integration"))
    api(project(":athena:runtime"))
    api(project(":athena:connector-weather"))
    api(project(":athena:connector-status"))
    implementation(project(":core:model"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.json)

    // AthenaCoordinator/AthenaWorker live here (not :app) so feature:settings — which already
    // depends on core:data — can inject the coordinator directly instead of launching a dedicated
    // Athena activity/screen. See the 2026-09-30 architecture correction: Athena has no UI of its
    // own; ATLAS (this app, via its own Settings) owns the user-facing controls.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // JVM unit tests (SettingsRepository with a temp-file DataStore).
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.google.truth)
    testImplementation(libs.json)
}

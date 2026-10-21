import java.io.FileInputStream
import java.util.Properties

plugins {
    id("atlas.android.library")
    id("atlas.android.hilt")
}

// Supabase project URL/key, read from supabase.properties (gitignored, never committed) — same
// pattern as keystore.properties in app/build.gradle.kts. On a machine without the file, the
// memory subsystem simply has no backend configured instead of the build failing.
val supabasePropsFile = rootProject.file("supabase.properties")
val supabaseProps = Properties().apply {
    if (supabasePropsFile.exists()) FileInputStream(supabasePropsFile).use { load(it) }
}

android {
    namespace = "com.unyx.atlas.memory"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "SUPABASE_URL", "\"${supabaseProps.getProperty("SUPABASE_URL", "")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${supabaseProps.getProperty("SUPABASE_ANON_KEY", "")}\"")
        // OAuth Client ID of type "Web application" from Google Cloud — the audience Supabase
        // expects on the Google ID token. Not a secret (safe to embed in a client app), just kept
        // in the same gitignored file as everything else backend-related.
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${supabaseProps.getProperty("GOOGLE_WEB_CLIENT_ID", "")}\"")
    }
}

dependencies {
    implementation(project(":core:security"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.json)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.google.truth)
}

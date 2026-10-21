// AGP 9 ships built-in Kotlin (default KGP). Bump it to our target Kotlin so we get the newest
// language version AND a matching Compose compiler. See https://kotl.in/gradle/agp-built-in-kotlin
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

// Declare plugins on the build classpath (apply false) so convention plugins in build-logic
// can apply them by id in each module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// One entry point for local validation and CI; real-device acceptance is recorded separately.
tasks.register("validateAthena") {
    group = "verification"
    description = "Validates ATHENA boundaries, contracts, integration and the debug APK."
    dependsOn(":athena:core:test", ":athena:runtime:test", ":athena:connector-weather:test",
        ":athena:connector-status:test", ":core:athena-integration:test", ":core:data:testDebugUnitTest",
        ":app:assembleDebug", ":app:assembleDebugAndroidTest")
}

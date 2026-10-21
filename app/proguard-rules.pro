# Project-specific R8/ProGuard rules for the release build.
#
# The libraries we use (Hilt, Room, Jetpack Compose, Coil, DataStore, Kotlin coroutines) ship their
# own "consumer" R8 rules, and manifest-declared components (Activity) are kept automatically. Add
# targeted -keep rules below if R8 ever strips or renames something used via reflection.

# WorkManager persists its jobs in its own internal Room database (WorkDatabase) — this app has no
# Room database of its own, but androidx.work pulls in room-runtime transitively regardless. R8's
# default optimizations were found (device-verified, release build) to strip the no-arg constructor
# Room's generated WorkDatabase_Impl needs, throwing NoSuchMethodException from
# LauncherApplication.onCreate() before the app ever shows a screen.
-keep class * extends androidx.room.RoomDatabase
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}

# CameraX (core/camera2/lifecycle/view) and ML Kit Barcode Scanning — Atlas Connect's QR pairing
# flow (core/chat-remote's pairing screens). Same class of issue as the Room rule above: both
# libraries resolve some of their own implementation classes by reflection rather than a direct
# call site R8 can see (CameraX's Camera2Config.DefaultProvider is discovered via a manifest
# <meta-data> entry, not referenced from our code at all; ML Kit's barcode model loads through
# Play Services' dynamic module system). Untested against this app's R8 config before this
# feature — added proactively rather than waiting to confirm the exact crash, since the pattern
# (works in debug, crashes release right where the library's own code first runs) already bit
# this project once with Room/WorkManager.
-keep class androidx.camera.camera2.Camera2Config$DefaultProvider { <init>(); }
-keep class androidx.camera.camera2.Camera2Config { *; }
-keep class androidx.camera.core.impl.CameraThreadConfig { *; }
-keep class com.google.mlkit.vision.barcode.** { *; }
-keep class com.google.mlkit.vision.common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled.** { *; }
-dontwarn com.google.android.gms.internal.mlkit_vision_barcode.**
-dontwarn com.google.android.gms.internal.mlkit_vision_barcode_bundled.**

# AGP 9 enables R8 full mode, which removes reflective ML Kit entry points and the constructors
# Firebase component discovery needs to instantiate ML Kit registrars. The scanner's
# MlKitInitProvider then crashes before any Activity can open. Keep the full ML Kit surface and
# those registrar constructors; this covers ML Kit common classes such as ExecutorSelector and
# MlKitContext, which the barcode-only rules above do not retain.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { void <init>(); }

# kotlinx.serialization — AtlasConnectScreen's Navigation3 migration (feature:connect's
# ConnectDestination sealed hierarchy, @Serializable so rememberNavBackStack can save/restore it
# across process death — the entire point of that migration). Ships its own consumer rules, but
# this project's established pattern (Room/WorkManager, ML Kit above) is that AGP 9's R8 full mode
# has twice already stripped reflective entry points consumer rules alone didn't fully cover —
# added proactively, untested against a release build on-device, same as the ML Kit rules were.
# Official recommended block from kotlinx.serialization's own README, not invented here.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <1>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    kotlinx.serialization.KSerializer serializer(...);
}

# R8/ProGuard rules for Ultra TV Native.
#
# Most libraries we use ship their own consumer rules (Hilt, Compose, Media3,
# Room, Coil…) so this file only lists what's specific to our codebase or
# where empirically R8 strips too aggressively.

# Keep all Kotlin metadata so kotlin-reflect-free libraries (Hilt, Room) can
# still introspect annotated classes.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations
-keepattributes Signature,InnerClasses,EnclosingMethod

# Hilt-generated components live under *_HiltComponents$* / Dagger*_* paths;
# they're already kept by Hilt's consumer rules, but keep entry points just in case.
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.hilt.work.HiltWorker { *; }

# Room entities + DAOs — Room's compiler generates the impls, but reflection
# happens at runtime for some queries.
-keep class com.ultratv.tv.nativeapp.data.db.** { *; }

# kotlinx.serialization uses reflection to find @Serializable classes' companion.
-keepclassmembers,allowobfuscation class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclasseswithmembers class **$$serializer { *; }
-keepclassmembers class ** {
    *** Companion;
}

# Media3 uses some service loader patterns.
-keepnames class androidx.media3.** { *; }

# OkHttp (used by Xtream/Stalker/M3U/RemoteConfig — hand-rolled clients, no Retrofit)
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Keep our DataStore-backed enum names so deserialisation stays stable.
-keepclassmembers enum com.ultratv.tv.nativeapp.data.prefs.** { *; }

# Don't obfuscate annotation classes — needed by Hilt/Compose tooling.
-keep @interface androidx.compose.runtime.Composable
-keep class kotlin.Metadata { *; }

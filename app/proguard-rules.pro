# Keep Media3 / ExoPlayer reflective entry points used by the Transformer pipeline.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.aivideostudio.**$$serializer { *; }
-keepclassmembers class com.aivideostudio.** {
    *** Companion;
}
-keepclasseswithmembers class com.aivideostudio.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Hilt / Dagger generated code
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep enums used through reflection in DataStore serialization
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

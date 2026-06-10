# T9 Gamepad Mapper — ProGuard rules

# Mantener clases del driver nativo (JNI)
-keep class com.t9mapper.service.GamepadNative { *; }
-keepclassmembers class com.t9mapper.service.GamepadNative {
    native <methods>;
}

# Mantener modelos de Room
-keep class com.t9mapper.data.model.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class **$$serializer {
    *;
}

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

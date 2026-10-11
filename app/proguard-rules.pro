# Release shrinking (R8). The libraries this app uses (Compose, Media3, OkHttp, kotlinx.serialization, Coil,
# WorkManager, DataStore) ship their own keep rules; these are only what the app itself needs.

# Readable stack traces in crash reports and logcat.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization: keep the generated serializers of the app's and twitch-core's @Serializable models.
-keepclassmembers @kotlinx.serialization.Serializable class com.s0undplus.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.s0undplus.**$$serializer { *; }

# OkHttp's optional platform integrations that are not on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

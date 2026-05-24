# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in in D:\Android\sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# Preserve data models for Gson Serialization/Deserialization
-keep class com.tvonnet.debridxtreamiptv.data.model.** { *; }

# Preserve Room Entities for Database schema and queries
-keep class com.tvonnet.debridxtreamiptv.data.local.entity.** { *; }

# Preserve API services and custom adapters
-keep class com.tvonnet.debridxtreamiptv.data.remote.** { *; }

# Additional basic rules for OkHttp / Retrofit just in case
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*

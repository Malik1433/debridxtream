# R8 / ProGuard rules for the release build.
#
# Context worth keeping: until 2026-08-11 no release build had ever been produced, so none of this
# was exercised. The first one crashed on launch with IllegalStateException inside
# com.google.gson.reflect.TypeToken — see the Gson block below for why, and never trust a startup
# measurement without confirming the app actually reaches home (a crash loop times as ~240ms).

# ── Attributes ──────────────────────────────────────────────────────────────────
# Signature carries generic types (Gson/Retrofit read them at runtime). InnerClasses and
# EnclosingMethod are what make an ANONYMOUS subclass keep its generic supertype — without them
# `object : TypeToken<List<Foo>>() {}` loses its type argument and Gson throws at construction.
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes Exceptions
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations

# ── Gson ────────────────────────────────────────────────────────────────────────
# THE startup crash. TypeToken subclasses must keep their signature; allowobfuscation/
# allowshrinking let R8 still rename and drop them, it just may not erase the generic type.
-dontwarn sun.misc.**
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
# Field names ARE the JSON keys for these types, so they cannot be renamed.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── Model classes read/written by reflection ────────────────────────────────────
# Gson maps JSON keys to FIELD NAMES; obfuscating them silently produces empty objects rather
# than a crash, which is worse. Every package whose types cross a Gson or Room boundary:
-keep class com.tvonnet.debridxtreamiptv.data.model.** { *; }
-keep class com.tvonnet.debridxtreamiptv.data.debrid.model.** { *; }
-keep class com.tvonnet.debridxtreamiptv.data.local.entity.** { *; }
-keep class com.tvonnet.debridxtreamiptv.data.remote.** { *; }

# ── WorkManager ─────────────────────────────────────────────────────────────────
# WorkManager persists the worker's CLASS NAME in its own database, so a rename breaks work that
# was already scheduled by an earlier install — including the EPG sync this app depends on.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# ── Retrofit (2.9 ships no rules for the coroutine path) ────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# Keep the service interfaces themselves — Retrofit builds a Proxy over them at runtime.
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

# ── Ktor (Phase 6: CIO powers the companion-pairing server) ─────────────────────
# Without these the release server silently fails to start.
-keep class io.ktor.** { *; }
-keep class io.ktor.server.cio.** { *; }
-dontwarn io.ktor.**
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

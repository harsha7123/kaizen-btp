# ── SAP BTP SDK ──────────────────────────────────────────────────────────────
-keep class com.sap.cloud.** { *; }
-keep class com.sap.smp.** { *; }
-dontwarn com.sap.**

# ── OkHttp / Okio ────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# ── ZXing barcode scanner ─────────────────────────────────────────────────────
-keep class com.journeyapps.** { *; }
-keep class com.google.zxing.** { *; }

# ── AndroidX / Material ───────────────────────────────────────────────────────
-keep class androidx.lifecycle.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <init>(); }

# ── Strip debug & verbose log calls in release builds ────────────────────────
# This removes Log.d / Log.v / Log.i calls entirely from the release APK so
# operator names, HU IDs, and URL paths are not visible via adb logcat.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}

# ── Keep data classes used for JSON parsing ───────────────────────────────────
-keepclassmembers class com.sap.droidx.data.** {
    <fields>;
    <init>(...);
}

# ── Logback (SAP SDK logging) ─────────────────────────────────────────────────
-keep class ch.qos.logback.** { *; }
-dontwarn ch.qos.logback.**
-dontwarn org.slf4j.**

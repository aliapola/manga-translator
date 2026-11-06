# ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }

# Room

# Keep data classes used for JSON/CSV import-export
-keep class com.mangatranslator.app.data.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Defense-in-depth keep rules (release builds currently keep minifyEnabled false to protect Kotlin 2.4 metadata).
-dontobfuscate
-keep class tech.kousozo.tahoetv.** { *; }
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class androidx.compose.** { *; }
-keep class androidx.graphics.path.** { *; }
-keep class androidx.media3.** { *; }
-keep class org.java_websocket.** { *; }
-dontwarn org.slf4j.**
-keep class dadb.** { *; }
-dontwarn okio.**
-dontwarn kotlin.**
-dontwarn org.bouncycastle.**

# FFmpeg renderer: created by reflection in DefaultRenderersFactory, native code calls back into it
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class androidx.media3.decoder.** { *; }

-keep class org.java_websocket.** { *; }
-dontwarn org.slf4j.**
-keep class dadb.** { *; }
-dontwarn okio.**
-dontwarn kotlin.**
-dontwarn org.bouncycastle.**

# FFmpeg renderer: created by reflection in DefaultRenderersFactory, native code calls back into it
-keep class androidx.media3.decoder.ffmpeg.** { *; }

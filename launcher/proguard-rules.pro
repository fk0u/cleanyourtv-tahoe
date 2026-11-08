-keep class tv.clean.home.** { *; }
-dontwarn org.nanohttpd.**
# :core references the ADB and WebSocket clients, which the launcher never calls (and doesn't ship)
-dontwarn dadb.**
-dontwarn org.java_websocket.**
-dontwarn tech.kousozo.tahoetv.core.TvAdb**
-dontwarn tech.kousozo.tahoetv.core.TvLink**

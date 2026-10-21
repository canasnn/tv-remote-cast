# Java-WebSocket uses reflection-free code but logs via SLF4J (optional dependency).
-dontwarn org.slf4j.**

# kotlinx.serialization: keep generated serializers of the shared protocol.
-keepclassmembers class com.ruterika.protocol.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ruterika.protocol.**$$serializer { *; }

# R8's optimizer rewrote the synchronized block in Java-WebSocket's WebSocketServer.run()
# into code that ART rejects ("expected to be within a catch-all ... monitor is held"),
# crashing the receiver on Android 14 TVs. Shrinking and renaming stay on.
-dontoptimize

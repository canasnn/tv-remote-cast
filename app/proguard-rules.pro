# ─── Encoding & Source Info ───────────────────────────────────────────────────
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable,Signature,Exceptions

# ─── Your App Classes ─────────────────────────────────────────────────────────
-keep public class com.canasnn.tvremote.App { *; }
-keep class com.canasnn.tvremote.androidLib.** { *; }
-keep class com.connectsdk.** { *; }

# ─── Native Methods ───────────────────────────────────────────────────────────
-keepclasseswithmembernames class * {
    native <methods>;
}

# ─── Android Resources ────────────────────────────────────────────────────────
-keepclassmembers class **.R$* {
    public static <fields>;
}

# ─── Enums ────────────────────────────────────────────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ─── Suppress Log in Release ──────────────────────────────────────────────────
-assumenoexternalsideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}

# ─── Suppress Missing Class Warnings (3rd party libs) ────────────────────────
-dontwarn com.amazon.**
-dontwarn javax.**
-dontwarn org.ietf.**
-dontwarn org.apache.**
-dontwarn sun.security.**
-dontwarn java.lang.management.**
-dontwarn okhttp3.internal.annotations.**
# kotlinx.serialization: keep generated serializers of the shared receiver protocol.
-keepclassmembers class com.ruterika.protocol.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ruterika.protocol.**$$serializer { *; }

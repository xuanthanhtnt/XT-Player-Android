# smbj, its DCE/RPC companion and Bouncy Castle use reflection (event bus, security providers, packet factories).
-keep class com.hierynomus.** { *; }
-keep class com.rapid7.client.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.** { *; }

# FFmpeg decoder: loaded reflectively by DefaultRenderersFactory, calls back from JNI.
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# kotlinx.serialization models
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers @kotlinx.serialization.Serializable class com.xuanthanhtnt.xtplayer.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.xuanthanhtnt.xtplayer.**$$serializer { *; }

# Desktop-JVM-only references inside the SMB / RPC stack (JNDI, GSS, javax.annotation, Guava internals…).
-dontwarn javax.**
-dontwarn java.lang.management.**
-dontwarn org.ietf.jgss.**
-dontwarn sun.**
-dontwarn org.slf4j.**
-dontwarn com.google.errorprone.**
-dontwarn com.google.j2objc.**
-dontwarn org.checkerframework.**
-dontwarn com.google.common.**
-dontwarn org.apache.commons.**
-dontwarn com.hierynomus.**
-dontwarn com.rapid7.**
-dontwarn org.bouncycastle.**
-dontwarn net.engio.**
-dontwarn kotlinx.serialization.**

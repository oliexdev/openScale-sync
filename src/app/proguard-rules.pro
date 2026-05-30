# ##################################################################################
# Allow shrinking, but prevent obfuscation (renaming).
# minifyEnabled is on for resource shrinking + dead-code removal; class/member names
# are preserved so the reflection-heavy MQTT stack (HiveMQ/Netty) keeps working.
# ##################################################################################
-dontobfuscate
-keepnames class * { *; }
-keepclassmembernames class * { *; }

# Keep runtime metadata needed for reflection / generics / stack traces.
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes SourceFile,LineNumberTable

# Safety net: fully keep reflection-/proxy-/codegen-based libs so shrinking never
# removes classes or members they resolve dynamically at runtime.
# MQTT stack (HiveMQ + transitive Netty / RxJava2 / Dagger / reactive-streams / JCTools):
-keep class com.hivemq.** { *; }
-keep class io.netty.** { *; }
-keep class org.jctools.** { *; }
-keep class io.reactivex.** { *; }
-keep class org.reactivestreams.** { *; }
-keep class dagger.** { *; }
# HTTP / JSON stack (Retrofit dynamic proxies, Gson reflection, OkHttp / Okio):
-keep class retrofit2.** { *; }
-keep class com.google.gson.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
# App data classes (de)serialized by Gson via reflection — keep their fields intact:
-keep class com.health.openscale.sync.core.datatypes.** { *; }
# Gson has no consumer rules: keep every model class that has @SerializedName fields
# fully intact (fields AND accessors), covering all JSON models regardless of package
# (e.g. Wger responses) and any added later.
-if class * { @com.google.gson.annotations.SerializedName <fields>; }
-keep class <1> { *; }

# Jetpack Compose: never strip @Composable functions or their enclosing classes.
-keepclasseswithmembers public class * {
    @androidx.compose.runtime.Composable <methods>;
}
# --- R8 missing-class suppression for Netty's optional deps (via HiveMQ MQTT client).
# These backends (brotli, zstd, protobuf, log4j/slf4j, jboss-marshalling, native epoll/tcnative,
# lz4, jetty alpn/npn, reactor blockhound, GraalVM svm) are not used/present on Android.
# Wildcard form of AGP's generated build/outputs/mapping/*/missing_rules.txt.
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.google.protobuf.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn io.netty.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.eclipse.jetty.**
-dontwarn org.jboss.marshalling.**
-dontwarn org.osgi.annotation.bundle.**
-dontwarn org.slf4j.**
-dontwarn reactor.blockhound.**

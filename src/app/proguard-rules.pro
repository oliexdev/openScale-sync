# ##################################################################################
# R8: shrink + optimize + obfuscate.
#
# Google Play grades the shipped bundle on "app optimization" (code optimization and
# obfuscation percentage); a blanket `-dontobfuscate` / `-keepnames class * { *; }`
# scores 0 % and drags the listing below Play's threshold. So nothing is kept
# wholesale any more — every keep below names the exact reflective contract that
# would otherwise break, and says why.
# ##################################################################################

# Keep runtime metadata needed for reflection / generics / readable stack traces.
# (Deobfuscate a trace with `retrace mapping.txt trace.txt`; the mapping file lands in
#  app/build/outputs/mapping/<buildType>/mapping.txt and MUST be archived per release.)
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ##################################################################################
# On-disk schemas — renaming these silently invalidates data users already have.
# ##################################################################################

# Gson writes the retry queue and the export ledger into SharedPreferences as JSON,
# and reads them back after an app update. The FIELD NAMES are the on-disk schema:
# rename them and every persisted entry becomes unreadable (ledger reset => re-push
# storm / undetected deletes; queue reset => dropped ops). Only the field names are
# pinned; everything else about these classes is still optimized.
-keepclassmembers class com.health.openscale.sync.core.datatypes.** { <fields>; }
-keepclassmembers class com.health.openscale.sync.core.service.PendingOp { <fields>; }
-keepclassmembers class com.health.openscale.sync.core.service.ServiceInterface$LedgerEntry { <fields>; }

# SyncDirection is persisted by `.name` and read back with `valueOf()` — across an app
# update, so the constant names must survive obfuscation or the read throws.
-keepclassmembernames class com.health.openscale.sync.** extends java.lang.Enum { <fields>; }

# ##################################################################################
# Netty (pulled in by the HiveMQ MQTT client) — genuinely reflection-bound fields.
# ##################################################################################
# These two are HiveMQ's OFFICIAL Android rules, verbatim from
# https://hivemq.github.io/hivemq-mqtt-client/docs/installation/android/ — keep them in
# sync with that page rather than hand-tuning them. The MQTT client does not ship them as
# consumer rules (its own hivemq-mqtt-client.pro carries only a -dontwarn), so they have to
# live here. Why they are needed: Netty resolves its own members by NAME at class-init time
# and throws when the name is gone — fields via AtomicIntegerFieldUpdater.newUpdater(
# X.class, "refCnt") and helpers R8 cannot see through (ReferenceCountUpdater
# .getUnsafeOffset, shaded JCTools UnsafeAccess.fieldOffset(clz, "producerIndex")), methods
# via ResourceLeakDetector.addExclusions(AbstractByteBufAllocator.class,
# "toLeakAwareBuffer"). Dropping the method half was verified to crash the app on the first
# MQTT connect:
#   ExceptionInInitializerError -> IllegalArgumentException: Can't find
#   '[toLeakAwareBuffer]' in io.netty.buffer.AbstractByteBufAllocator
# Only member NAMES are pinned: Netty's CLASS names are still obfuscated (766/767) and
# unused Netty code is still shrunk away. HiveMQ itself needs nothing — no name-based
# reflection anywhere in the artifact — and is obfuscated in full.
-keepclassmembernames class io.netty.** { *; }
-keepclassmembers class org.jctools.** { *; }

# Everything else on the classpath ships its own consumer rules, which AGP merges in
# automatically — do NOT copy them in here (verified against
# build/outputs/mapping/<buildType>/configuration.txt, 76 merged sections): Retrofit
# (META-INF/proguard/retrofit2.pro), OkHttp (and Okio, covered by OkHttp's rules under R8),
# Gson (bundled since 2.11 — covers @SerializedName models and TypeToken subclasses),
# Health Connect connect-client (bundled since 1.1.0-alpha05 — proto GeneratedMessageLite
# fields, ErrorCode, Permission), WorkManager (keeps ListenableWorker subclass names + ctor,
# so PeriodicSyncWorker survives an app update), Timber, RxJava2, Compose/AndroidX.
# CustomActivityOnCrash documents: "No need to add special rules, the library should work
# even with obfuscation".

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

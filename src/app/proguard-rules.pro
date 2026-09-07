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
# Netty resolves its own members by NAME at class-init time and throws when the name
# is gone. Fields: AtomicIntegerFieldUpdater.newUpdater(X.class, "refCnt") plus helpers
# R8 cannot see through (ReferenceCountUpdater.getUnsafeOffset, and the shaded JCTools
# UnsafeAccess.fieldOffset(clz, "producerIndex")). Methods: ResourceLeakDetector
# .addExclusions(AbstractByteBufAllocator.class, "toLeakAwareBuffer") and the same in
# ReferenceCountUtil / AdvancedLeakAwareByteBuf -- verified: obfuscating them crashes the
# app on the first MQTT connect with
#   ExceptionInInitializerError -> IllegalArgumentException: Can't find
#   '[toLeakAwareBuffer]' in io.netty.buffer.AbstractByteBufAllocator
# So member NAMES are pinned for io.netty. Netty's CLASS names are still obfuscated and
# unused Netty code is still shrunk away; HiveMQ needs nothing (no name-based reflection
# anywhere in the artifact) and is obfuscated in full.
-keepclassmembernames class io.netty.** { *; }
-keepclassmembernames class org.jctools.** { *; }

# HiveMQ itself needs no keeps: it is Dagger-generated (compile-time) and hands Netty
# a `NioSocketChannel::new` method reference rather than a reflective channel class.
# Retrofit / OkHttp / Okio / Gson ship their own consumer rules — do not duplicate them
# here (Gson's rules already cover @SerializedName models and TypeToken subclasses).

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

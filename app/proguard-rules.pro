# R8 is on for release for speed, not size — see the note on isMinifyEnabled in
# build.gradle.kts. These rules keep it away from everything that finds classes
# by name at runtime, where a missing class shows up as a crash on a device
# rather than as a build error.

# Nothing is renamed. Stack traces stay readable, and every lookup by name —
# enum names in preferences, Media3 custom commands, library reflection — keeps
# working without a rule of its own.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*

# ---- Script engines and extractors ------------------------------------------
# Rhino compiles and runs YouTube's player JavaScript and binds Java classes into
# it reflectively; NewPipe and InnerTubeX drive it and parse with nanojson/jsoup.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class org.schabi.newpipe.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.metrolist.** { *; }

# ---- QuickJS --------------------------------------------------------------------
# The module runner evaluates addon JavaScript in a native VM: the Kotlin wrapper
# crosses into libquickjs through JNI and hands native code back Java callbacks
# and bound functions by name.
-keep class com.dokar.quickjs.** { *; }
-keep class com.quickjs.** { *; }
# Bound Kotlin functions are invoked from the VM by the name the binding carries,
# not by a direct call site.
-keep,includedescriptorclasses class * extends com.dokar.quickjs.binding.FunctionBinding { *; }
-keep,includedescriptorclasses class * extends com.dokar.quickjs.binding.AsyncFunctionBinding { *; }

# ---- Native and crypto --------------------------------------------------------
# ONNX Runtime and QuickJS are called from JNI, which R8 cannot see. SMBJ picks
# its BouncyCastle providers and event bus handlers reflectively.
-keep class ai.onnxruntime.** { *; }
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
# The vendored FFmpeg extension's JNI calls back into this by name to grow
# its output buffer (native/ffmpeg/ffmpeg_jni.cc, JNI_OnLoad).
-keep,includedescriptorclasses class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder {
    private java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
}

# ---- Serialization --------------------------------------------------------------
# protobuf-lite reads its generated messages' fields reflectively.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
# Ktor resolves serializers and engine pieces by type at runtime.
-keep class io.ktor.** { *; }

# ---- Kotlinx Serialization ------------------------------------------------------
# The compiler plugin generates a `$serializer` beside every @Serializable class
# and a `serializer()` on its companion; the runtime finds both by name, so
# neither is visible to R8 as a reference. These are the rules kotlinx.serialization
# publishes, plus a keep for the classes themselves: a model that only exists to
# be deserialized has no other call site, and without the class keep R8 is free
# to remove it and leave the serializer pointing at nothing.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep class kotlinx.serialization.** { *; }
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,allowoptimization @kotlinx.serialization.Serializable class ** { *; }
-keepclassmembers class **$$serializer {
    *** descriptor;
    kotlinx.serialization.KSerializer serializer(...);
}
# The app's wire and database models are read back by field name from JSON and
# Preferences; keep the whole model package rather than trusting a call site.
-keep class com.music.bitchord.data.model.** { *; }
-keep @kotlinx.serialization.Serializable class com.music.bitchord.** { *; }
-keep @kotlinx.serialization.Serializable class com.my.kizzy.** { *; }

# Enums are written to preferences and saved state as names. `-dontobfuscate`
# keeps the names, but R8 may still strip the synthetic lookup methods.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- HTTP: OkHttp and Okio ------------------------------------------------------
# OkHttp picks its TLS platform implementation and public-suffix database by
# class name at startup. Its refs to JVM-only conscrypt/OpenJDK classes are
# optional and absent on Android, which is a warning rather than a fault.
-keep class okhttp3.internal.platform.** { *; }
-keep class okhttp3.internal.publicsuffix.** { *; }
-keep class okhttp3.internal.tls.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Media3 / ExoPlayer ---------------------------------------------------------
# The session layer crosses process boundaries as Bundles and is rebuilt by class
# name; the HLS/DASH sources create their extractors from manifest MIME types,
# and the FFmpeg extension is entered from JNI. Media3's own consumer rules cover
# the rest of the player, so this is limited to what reflection can reach.
-keep class androidx.media3.session.** { *; }
-keep class androidx.media3.exoplayer.hls.** { *; }
-keep class androidx.media3.exoplayer.dash.** { *; }
-keep class androidx.media3.exoplayer.audio.** { *; }
-keep class androidx.media3.datasource.okhttp.** { *; }
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keepclassmembers class androidx.media3.** implements android.os.Parcelable {
    public static final ** CREATOR;
}
# The media button receiver and playback service are named in the manifest, but
# their subclasses still need their platform constructors.
-keepclassmembers class * extends androidx.media3.session.MediaSessionService {
    public <init>(...);
}
-keepclassmembers class * extends androidx.media3.session.MediaLibraryService {
    public <init>(...);
}

# ---- WebView bridges ------------------------------------------------------------
# PoToken minting and the Spotify token page call back into these from JavaScript.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- JVM-only APIs ------------------------------------------------------------
# Referenced by desktop-only paths in Rhino (JSR-223, java.beans, dynalink),
# mbassy's EL filters, SMBJ's Kerberos and Ktor's IntelliJ debugger probe. None
# of them exist on Android and none of those paths run here — the debug build,
# which R8 never touches, has always lived without them.
-dontwarn java.beans.**
-dontwarn java.lang.management.**
-dontwarn javax.el.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn org.ietf.jgss.**

# ---- "BitChord for Wear OS": the ADB client that installs the watch app ----
# libadb-android drives the pairing and transport through Conscrypt and SPAKE2,
# both of which reach their native halves by name.
-keep class io.github.muntashirakon.adb.** { *; }
-keep class org.conscrypt.** { *; }
-keep class com.android.org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-dontwarn com.android.org.conscrypt.**
-dontwarn io.github.muntashirakon.adb.**

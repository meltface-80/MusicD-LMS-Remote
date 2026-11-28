# R8 on the release build: the libraries (Compose above all) are optimised and trimmed; Mallard's own code is kept
# whole and named as written, so every class the manifest names stays exactly as it is.
-dontobfuscate
-keep class com.musicd.** { *; }
# The Opus decoder the app builds itself (Mandarin's rule): its JNI is named after these classes, and DefaultRenderersFactory
# finds LibopusAudioRenderer by name. The native code calls back into SimpleDecoderOutputBuffer.init by name, which R8
# can't see from Java — Mandarin's first Opus track ended the player without it.
-keep class androidx.media3.decoder.opus.** { *; }
-keep class androidx.media3.decoder.SimpleDecoderOutputBuffer { *; }
-keepclasseswithmembernames class * { native <methods>; }

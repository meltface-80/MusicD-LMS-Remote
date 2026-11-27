# R8 on the release build: the libraries (Compose above all) are optimised and trimmed; Mallard's own code is kept
# whole and named as written, so every class the manifest names stays exactly as it is.
-dontobfuscate
-keep class com.musicd.** { *; }

# Media3's Opus decoder module, in the app

These four files are `libraries/decoder_opus` from
[androidx/media](https://github.com/androidx/media) at 1.8.0 (the Media3
version the app uses), unchanged, under their Apache 2.0 headers — carried
over from Mandarin, which carries them for the same reason. Media3 does not
publish this module as a library — it must be built with libopus — so it is
built by the app's own native build (`src/main/cpp/CMakeLists.txt`, which
builds libopus 1.5.2, its SHA-256 pinned).

Why: Android's own Opus decoder gives 16-bit PCM. This one decodes to float
when the audio sink is float, so the server's Opus 256 (encoded from 32-bit
float at 48 kHz) reaches the phone — and a USB DAC — at 24/48. The package
name is Media3's because the JNI symbols in `opus_jni.cc` are named after it,
and `DefaultRenderersFactory` finds `LibopusAudioRenderer` by this name.

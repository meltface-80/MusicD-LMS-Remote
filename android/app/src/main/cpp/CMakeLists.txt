# Mallard's native code (Mandarin's, carried over):
#  - mallardusb: the USB audio driver (usb_jni.cc, its JNI names Mallard's). usbdevfs ioctls on the device node Android
#    hands the app for a DAC it has permission for; isochronous transfers Java has no call for.
#  - opusV2JNI: Media3's Opus JNI wrapper (opus_jni.cc, from androidx/media 1.8.0, Apache 2.0) over libopus, built
#    static, which androidx.media3.decoder.opus.OpusLibrary loads: Android's own Opus decoder gives 16-bit, this one
#    float, so the server's Opus 256 reaches the phone at 24/48.
cmake_minimum_required(VERSION 3.22.1 FATAL_ERROR)
project(mallardNative C CXX)
set(CMAKE_CXX_STANDARD 11)

include(FetchContent)
# Where libopus comes from, tried in turn: the copy CI fetched and cached ahead of the build (OPUS_TARBALL,
# .github/workflows/mallard.yml), else its GitHub release, else Xiph's own downloads. Whichever it is, its SHA-256 must
# be this one, so no copy but libopus 1.5.2's own is ever built.
if(DEFINED ENV{OPUS_TARBALL} AND EXISTS "$ENV{OPUS_TARBALL}")
  set(OPUS_URLS "$ENV{OPUS_TARBALL}")
else()
  set(OPUS_URLS
    https://github.com/xiph/opus/releases/download/v1.5.2/opus-1.5.2.tar.gz
    https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz)
endif()
FetchContent_Declare(opus
  URL ${OPUS_URLS}
  URL_HASH SHA256=65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1)
# A decoder only: no docs, tests or programs, and no float-free build — the whole point is opus_multistream_decode_float.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(opus)

find_library(android_log_lib log)
add_library(mallardusb SHARED usb_jni.cc)
target_link_libraries(mallardusb PRIVATE android PRIVATE ${android_log_lib})
# 16 KB ELF alignment, as Android 15 asks of every native library.
target_link_options(mallardusb PRIVATE "-Wl,-z,max-page-size=16384")

add_library(opusV2JNI SHARED opus_jni.cc)
target_link_libraries(opusV2JNI PRIVATE android PRIVATE opus PRIVATE ${android_log_lib})
target_link_options(opusV2JNI PRIVATE "-Wl,-z,max-page-size=16384")

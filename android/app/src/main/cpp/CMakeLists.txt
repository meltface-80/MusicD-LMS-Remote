# Mallard's native code: the USB audio driver (usb_jni.cc, Mandarin's own, its JNI names Mallard's). usbdevfs ioctls on
# the device node Android hands the app for a DAC it has permission for; isochronous transfers Java has no call for.
cmake_minimum_required(VERSION 3.22.1 FATAL_ERROR)
project(mallardNative CXX)
set(CMAKE_CXX_STANDARD 11)

find_library(android_log_lib log)
add_library(mallardusb SHARED usb_jni.cc)
target_link_libraries(mallardusb PRIVATE android PRIVATE ${android_log_lib})
# 16 KB ELF alignment, as Android 15 asks of every native library.
target_link_options(mallardusb PRIVATE "-Wl,-z,max-page-size=16384")

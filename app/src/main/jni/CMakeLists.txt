set(ANDROID_PLATFORM "android-16" CACHE STRING "Android platform" FORCE)
cmake_minimum_required(VERSION 3.22.1)
project(medianest_native LANGUAGES C)

add_library(ass SHARED IMPORTED)
set_target_properties(ass PROPERTIES
    IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libass.so")

add_library(assjni SHARED ass_jni.c)
target_include_directories(assjni PRIVATE "${CMAKE_SOURCE_DIR}/include")
target_link_libraries(assjni ass log)
target_link_options(assjni PRIVATE "-Wl,--hash-style=both")
set_target_properties(assjni PROPERTIES
    C_STANDARD 99
    C_STANDARD_REQUIRED YES)

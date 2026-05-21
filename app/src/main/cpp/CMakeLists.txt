# Sussurro by Eyed — Whisper JNI bridge build script.
#
# This file pulls whisper.cpp (which transitively brings in GGML) at configure
# time via FetchContent, then compiles a single shared library that links
# everything together. The Kotlin side is in
# `de/aploi/sussurrobyeyed/whisper/WhisperLib.kt`.
#
# Layout mirrors `whisper.cpp/examples/whisper.android/lib/src/main/jni/whisper/CMakeLists.txt`:
# whisper.cpp's source is compiled DIRECTLY into our shared library, with
# GGML linked alongside. Building whisper as a separate library and linking
# to it (the more "obvious" CMake approach) was producing zero-segment
# transcripts; the official Android example builds the way it does for a
# reason.

cmake_minimum_required(VERSION 3.22.1)
project(sussurro_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# --- whisper.cpp source (FetchContent ----------------------------------------)
#
# Pinned to v1.8.4 (March 2026 release). We do NOT call
# FetchContent_MakeAvailable here because that would add whisper.cpp's own
# CMakeLists.txt as a subdirectory and produce a separate libwhisper.so;
# instead we just want the sources on disk to compile directly.

include(FetchContent)
FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG        v1.8.4
    GIT_SHALLOW    TRUE
)
FetchContent_GetProperties(whisper_cpp)
if (NOT whisper_cpp_POPULATED)
    FetchContent_Populate(whisper_cpp)
endif()

set(WHISPER_LIB_DIR ${whisper_cpp_SOURCE_DIR})

# --- GGML (built via whisper.cpp's bundled ggml subdir) ----------------------

set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE         OFF CACHE BOOL "" FORCE)
set(GGML_BLAS              OFF CACHE BOOL "" FORCE)
set(GGML_CUDA              OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN            OFF CACHE BOOL "" FORCE)
set(GGML_METAL             OFF CACHE BOOL "" FORCE)
set(GGML_KOMPUTE           OFF CACHE BOOL "" FORCE)
set(GGML_HIPBLAS           OFF CACHE BOOL "" FORCE)
# Build GGML as part of the same shared library so static linker doesn't
# strip the CPU backend's self-registration constructor.
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL        OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_LIB_DIR}/ggml ${CMAKE_CURRENT_BINARY_DIR}/ggml)

# --- JNI shared library ------------------------------------------------------

add_library(sussurro-whisper SHARED
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    whisper-jni.cpp
)

# Critical: whisper.cpp's source uses #ifdef GGML_USE_CPU to register the CPU
# backend at init time. Without this define the backend list is empty and
# inference silently produces zero segments.
target_compile_definitions(sussurro-whisper PUBLIC GGML_USE_CPU)
# whisper.cpp expects WHISPER_VERSION to be passed via -D when compiled
# outside its own CMake; otherwise `whisper_print_system_info` won't link.
target_compile_definitions(sussurro-whisper PRIVATE WHISPER_VERSION="1.8.4")

target_include_directories(sussurro-whisper PRIVATE
    ${WHISPER_LIB_DIR}/include
    ${WHISPER_LIB_DIR}/src
    ${WHISPER_LIB_DIR}/ggml/include
    ${WHISPER_LIB_DIR}/ggml/src
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu
)

# Leave ARM-specific tuning to the GGML CMake defaults (which detect via
# `cpufeatures` at build time). Our manual -march=armv8.2-a+fp16 was correlated
# with zero-segment transcripts on multiple Snapdragon devices.

# 16 KB page-size compatibility (required for Google Play uploads targeting
# Android 15+ from 2025-11-01). NDK 27 still defaults LOAD segments to 4 KB
# alignment; NDK 28 would do this for us, but we're pinned to 27.2 because 28
# produced zero-segment whisper output. Apply the flag in both Debug and
# Release builds so `app-debug.apk` is also conformant.
target_link_options(sussurro-whisper PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(sussurro-whisper PRIVATE
        -O3
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -ffunction-sections
        -fdata-sections
    )
    target_link_options(sussurro-whisper PRIVATE
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
    )
endif()

find_library(LOG_LIB log)
find_library(ANDROID_LIB android)

target_link_libraries(sussurro-whisper PRIVATE
    ggml
    ${LOG_LIB}
    ${ANDROID_LIB}
)

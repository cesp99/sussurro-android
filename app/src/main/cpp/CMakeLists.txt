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

# OpenMP: ggml's manual thread pool is wasteful when threads land on
# heterogeneous Android cores (perf + efficiency). OpenMP delegates to
# libomp.so which is part of the NDK toolchain (r21+) and yields tighter
# per-iteration scheduling, especially for the encoder's matmul loops.
set(GGML_OPENMP            ON  CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE         OFF CACHE BOOL "" FORCE)
set(GGML_BLAS              OFF CACHE BOOL "" FORCE)
set(GGML_CUDA              OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN            OFF CACHE BOOL "" FORCE)
set(GGML_METAL             OFF CACHE BOOL "" FORCE)
set(GGML_KOMPUTE           OFF CACHE BOOL "" FORCE)
set(GGML_HIPBLAS           OFF CACHE BOOL "" FORCE)

# --- CPU dispatch model -----------------------------------------------------
#
# Two ABIs, two strategies:
#
#   arm64-v8a (modern phones)
#     Build *all* of ggml's pre-canned Android CPU variants
#     (`android_armv8.0_1` baseline → `android_armv9.2_2` with SVE2 + SME +
#     i8mm). Each one ships as a separate `libggml-cpu-<tag>.so` MODULE in
#     the APK. At init the JNI calls `ggml_backend_load_all_from_path` on
#     the app's nativeLibraryDir; ggml dlopens each candidate, asks it for
#     a `ggml_backend_score()`, and keeps the highest-scoring variant that
#     reports compatibility with the running CPU. Net effect: a Snapdragon
#     8 Gen 2 grabs `android_armv8.6_1` (DOTPROD + FP16 + i8mm) for ~2×
#     more encoder throughput than the dotprod+fp16 baseline, while a
#     Snapdragon 765 / 888 still works on `android_armv8.2_2` without
#     crashing with SIGILL.
#
#   armeabi-v7a (legacy 32-bit)
#     Keep the original "static-link + bundled CPU backend" arrangement.
#     The Android variant list is arm64-only, and the 32-bit market on
#     modern Android is small enough that adding multiple variants here
#     isn't worth the maintenance cost.
# 16 KB page-size alignment (Android 15+ / Google Play 2025-11-01 requirement).
# NDK 27 still defaults LOAD segments to 4 KB alignment; we have to ask the
# linker to bump it. Use `add_link_options` at the directory level so this
# propagates to every shared/module library produced under here — including
# the ggml-base / ggml / ggml-cpu-* MODULEs that the next add_subdirectory
# call will create. Anything we miss here still ships with 4 KB segments
# and would be rejected by Play Console.
add_link_options(
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ALL_VARIANTS ON  CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL       ON  CACHE BOOL "" FORCE)

    # Prune ggml's stock variant list to just the two we actually want to
    # ship: a "normal" build that covers every arm64 Android phone from
    # ~2018 onwards (dotprod + fp16), and a "flagship" build that adds the
    # i8mm matmul-int8 kernels for Snapdragon 8 Gen 1+ / Dimensity 9000+ /
    # Apple A15+. Everything else (armv8.0_1 baseline, armv8.2_1 dotprod-
    # only, the armv9 SVE/SME tiers) costs ~4 MB of APK weight per variant
    # with negligible real-world device coverage, so we comment them out
    # at configure time.
    #
    # Implementation is a textual edit of ggml/src/CMakeLists.txt — uglier
    # than a CMake option flag, but ggml doesn't expose one. The REPLACE is
    # idempotent: if FetchContent re-populates the source, we patch again;
    # if it's already patched, the originals aren't found and the file is
    # rewritten unchanged.
    set(_ggml_variants_file "${WHISPER_LIB_DIR}/ggml/src/CMakeLists.txt")
    file(READ "${_ggml_variants_file}" _ggml_variants_content)
    foreach(_skip
        "android_armv8.0_1"
        "android_armv8.2_1"
        "android_armv9.0_1"
        "android_armv9.2_1"
        "android_armv9.2_2"
    )
        string(REPLACE
            "ggml_add_cpu_backend_variant(${_skip}"
            "# pruned by sussurro: ggml_add_cpu_backend_variant(${_skip}"
            _ggml_variants_content
            "${_ggml_variants_content}"
        )
    endforeach()
    file(WRITE "${_ggml_variants_file}" "${_ggml_variants_content}")
else()
    set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL       OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory(${WHISPER_LIB_DIR}/ggml ${CMAKE_CURRENT_BINARY_DIR}/ggml)

# --- JNI shared library ------------------------------------------------------

add_library(sussurro-whisper SHARED
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    whisper-jni.cpp
)

# whisper.cpp's source uses #ifdef GGML_USE_CPU to register the CPU backend
# at static-init time. We only need it when the CPU backend is *statically*
# linked into our SO — i.e. armeabi-v7a. On arm64-v8a we use GGML_BACKEND_DL
# and dlopen the right variant at runtime; defining GGML_USE_CPU there would
# wire in a non-existent built-in backend.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # Tell whisper-jni.cpp it should call ggml_backend_load_all_from_path
    # at init.
    target_compile_definitions(sussurro-whisper PRIVATE SUSSURRO_USE_BACKEND_DL)
else()
    target_compile_definitions(sussurro-whisper PUBLIC GGML_USE_CPU)
endif()
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

# Page-size alignment for `sussurro-whisper` is inherited from the
# directory-level `add_link_options` above (which also covers the ggml
# subdirectory's targets), so we don't need a per-target setting here
# anymore.

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

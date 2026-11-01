cmake_minimum_required(VERSION 3.22.1)
project(horizon_android_bootstrap LANGUAGES C CXX)

add_library(horizon_android SHARED
    src/main/cpp/horizon_bootstrap.cpp
)

target_compile_features(horizon_android PRIVATE cxx_std_17)
target_compile_options(horizon_android PRIVATE -Wall -Wextra -Werror=return-type)
target_link_libraries(horizon_android PRIVATE android log dl)

# Java Bridge uses LWJGL's Java memory APIs alongside Horizon's WGPU renderer.
# LWJGL ships desktop/Linux natives but no Android ABI, so this narrowly scoped
# JNI library provides the core memory functions only; it is not an OpenGL backend.
add_library(lwjgl SHARED
    ../tools/lwjgl_android/lwjgl_android.c
)
target_compile_features(lwjgl PRIVATE c_std_11)
target_compile_options(lwjgl PRIVATE -Wall -Wextra -Werror)

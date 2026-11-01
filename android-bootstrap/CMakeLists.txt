cmake_minimum_required(VERSION 3.22.1)
project(horizon_android_bootstrap LANGUAGES C CXX)

add_library(horizon_android SHARED
    src/main/cpp/horizon_bootstrap.cpp
)

target_compile_features(horizon_android PRIVATE cxx_std_17)
target_compile_options(horizon_android PRIVATE -Wall -Wextra -Werror=return-type)
target_link_libraries(horizon_android PRIVATE android log dl)

# Full LWJGL 3.4.1 core JNI surface, including libffi-backed callbacks.
# Built from pinned sources for Android API 26 by tools/build_lwjgl_android.sh.
set(LWJGL_ANDROID_LIBRARY "${CMAKE_CURRENT_LIST_DIR}/prebuilt/${ANDROID_ABI}/liblwjgl.so")
if(NOT EXISTS "${LWJGL_ANDROID_LIBRARY}")
    message(FATAL_ERROR "Missing ${LWJGL_ANDROID_LIBRARY}; run tools/build_lwjgl_android.sh first.")
endif()

add_library(lwjgl SHARED IMPORTED GLOBAL)
set_target_properties(lwjgl PROPERTIES
    IMPORTED_LOCATION "${LWJGL_ANDROID_LIBRARY}"
    IMPORTED_SONAME "liblwjgl.so"
)
target_link_options(horizon_android PRIVATE "-Wl,--no-as-needed")
target_link_libraries(horizon_android PRIVATE lwjgl)

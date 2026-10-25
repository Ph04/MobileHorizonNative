cmake_minimum_required(VERSION 3.22.1)
project(horizon_android_bootstrap LANGUAGES C CXX)

add_library(horizon_android SHARED
    src/main/cpp/horizon_bootstrap.cpp
)

target_compile_features(horizon_android PRIVATE cxx_std_17)
target_compile_options(horizon_android PRIVATE -Wall -Wextra -Werror=return-type)
target_link_libraries(horizon_android PRIVATE android log dl)

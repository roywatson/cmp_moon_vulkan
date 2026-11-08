cmake_minimum_required(VERSION 3.22.1)
project(moon_renderer LANGUAGES CXX)

set(REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../..")
set(GLSLC_EXECUTABLE "${CMAKE_ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc")
add_subdirectory("${REPO_ROOT}/renderer" "${CMAKE_CURRENT_BINARY_DIR}/renderer")

add_library(moon_renderer SHARED moon_renderer_jni.cpp "${REPO_ROOT}/renderer/thread/moon_render_thread.cpp")
target_compile_features(moon_renderer PRIVATE cxx_std_20)
target_compile_options(moon_renderer PRIVATE -Wall -Wextra -Werror -Wno-missing-field-initializers)
target_include_directories(moon_renderer PRIVATE "${REPO_ROOT}/renderer/thread")
target_link_libraries(moon_renderer PRIVATE moon_renderer_core android jnigraphics vulkan)

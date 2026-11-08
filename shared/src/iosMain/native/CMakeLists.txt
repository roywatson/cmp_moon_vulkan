cmake_minimum_required(VERSION 3.22)
# Builds libmoon_ios.a for one iOS SDK/arch (Gradle configures device and simulator
# separately). Kotlin/Native cinterop links it, together with MoltenVK, into Shared.
project(moon_ios LANGUAGES C CXX OBJCXX)

set(REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../..")
if(NOT MOLTENVK_LIBRARY OR NOT EXISTS "${MOLTENVK_LIBRARY}")
    message(FATAL_ERROR "MOLTENVK_LIBRARY must point at the slice's libMoltenVK.a")
endif()

include_directories(BEFORE "${REPO_ROOT}/third_party/MoltenVK/include")
add_subdirectory("${REPO_ROOT}/renderer" "${CMAKE_CURRENT_BINARY_DIR}/renderer")

set(MOON_RENDER_THREAD "${REPO_ROOT}/renderer/thread/moon_render_thread.cpp")
# The render thread needs Objective-C++ on Apple platforms for its per-frame autorelease pool.
set_source_files_properties("${MOON_RENDER_THREAD}" PROPERTIES LANGUAGE OBJCXX)
add_library(moon_ios_bridge STATIC moon_ios.mm "${MOON_RENDER_THREAD}")
target_compile_features(moon_ios_bridge PRIVATE cxx_std_20)
target_compile_options(moon_ios_bridge PRIVATE -fobjc-arc -Wall -Wextra -Wno-missing-field-initializers)
target_include_directories(moon_ios_bridge PRIVATE "${REPO_ROOT}/renderer/thread" "${CMAKE_CURRENT_LIST_DIR}")
target_link_libraries(moon_ios_bridge PRIVATE moon_renderer_core)

# One archive for cinterop: bridge + renderer core + MoltenVK. libtool merges archives.
# The simulator MoltenVK slice is fat (arm64 + x86_64); thin it to the arch being built.
set(MOON_IOS_LIBRARY "${CMAKE_BINARY_DIR}/libmoon_ios.a")
set(MOLTENVK_THIN "${CMAKE_BINARY_DIR}/libMoltenVK-${CMAKE_OSX_ARCHITECTURES}.a")
add_custom_command(
    OUTPUT "${MOLTENVK_THIN}"
    COMMAND sh -c "lipo '${MOLTENVK_LIBRARY}' -thin '${CMAKE_OSX_ARCHITECTURES}' -output '${MOLTENVK_THIN}' 2>/dev/null || cp '${MOLTENVK_LIBRARY}' '${MOLTENVK_THIN}'"
    DEPENDS "${MOLTENVK_LIBRARY}"
    VERBATIM)
add_custom_command(
    OUTPUT "${MOON_IOS_LIBRARY}"
    COMMAND libtool -static -no_warning_for_no_symbols -o "${MOON_IOS_LIBRARY}"
        "$<TARGET_FILE:moon_ios_bridge>" "$<TARGET_FILE:moon_renderer_core>" "${MOLTENVK_THIN}"
    DEPENDS moon_ios_bridge moon_renderer_core "${MOLTENVK_THIN}"
    VERBATIM)
add_custom_target(moon_ios ALL DEPENDS "${MOON_IOS_LIBRARY}")

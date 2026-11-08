cmake_minimum_required(VERSION 3.22)
# Desktop JNI library for the shared Vulkan renderer:
#  * macOS:   JAWT CAMetalLayer + MoltenVK (vendored, linked statically).
#  * Windows: JAWT HWND + the system Vulkan loader (headers/import lib from the LunarG SDK).
#  * Linux:   JAWT X11 window + the system Vulkan loader (libvulkan-dev, libx11-dev, glslc).
project(desktop_moon_renderer LANGUAGES C CXX)

set(REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../..")
set(MOON_RENDER_THREAD "${REPO_ROOT}/renderer/thread/moon_render_thread.cpp")

# JAVA_HOME must be the JDK that will *run* the app: JAWT is linked from it and its
# headers must match. Gradle passes the same JDK it uses for :desktopApp:run.
file(TO_CMAKE_PATH "${JAVA_HOME}" JAVA_HOME)
if(NOT JAVA_HOME OR NOT EXISTS "${JAVA_HOME}/include/jni.h")
    message(FATAL_ERROR "JAVA_HOME must point at a JDK with headers (got '${JAVA_HOME}')")
endif()

if(APPLE)
    enable_language(OBJCXX)
    set(MOLTENVK_ROOT "${REPO_ROOT}/third_party/MoltenVK")
    set(MOLTENVK_STATIC "${MOLTENVK_ROOT}/lib/macos-arm64_x86_64/libMoltenVK.a")

    # The shared renderer core compiles against MoltenVK's Vulkan headers.
    include_directories(BEFORE "${MOLTENVK_ROOT}/include")
    add_subdirectory("${REPO_ROOT}/renderer" "${CMAKE_CURRENT_BINARY_DIR}/renderer")

    # The render thread needs Objective-C++ on Apple platforms for its per-frame autorelease pool.
    set_source_files_properties("${MOON_RENDER_THREAD}" PROPERTIES LANGUAGE OBJCXX)
    add_library(desktop_moon_renderer SHARED desktop_jni.cpp desktop_bridge_macos.mm "${MOON_RENDER_THREAD}")
    target_compile_options(desktop_moon_renderer PRIVATE -fobjc-arc -Wall -Wextra -Wno-missing-field-initializers)
    target_include_directories(desktop_moon_renderer PRIVATE "${JAVA_HOME}/include/darwin")
    # MoltenVK is linked statically so the dylib has no runtime framework lookup to get wrong.
    # libjawt resolves to @rpath/libjawt.dylib; the Kotlin side loads the running JDK's jawt
    # first, so the already-loaded image is reused.
    target_link_libraries(desktop_moon_renderer PRIVATE
        "${MOLTENVK_STATIC}"
        "${JAVA_HOME}/lib/libjawt.dylib"
        "-framework Cocoa" "-framework QuartzCore" "-framework Metal" "-framework IOKit"
        "-framework IOSurface" "-framework CoreGraphics" "-framework Foundation")
    set_target_properties(desktop_moon_renderer PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
        BUILD_RPATH "${JAVA_HOME}/lib")
elseif(WIN32)
    # Install the LunarG Vulkan SDK (it sets VULKAN_SDK): FindVulkan takes the headers,
    # vulkan-1.lib, and glslc from it. The GPU driver supplies vulkan-1.dll at runtime.
    find_package(Vulkan REQUIRED)
    if(NOT EXISTS "${GLSLC_EXECUTABLE}" AND Vulkan_GLSLC_EXECUTABLE)
        set(GLSLC_EXECUTABLE "${Vulkan_GLSLC_EXECUTABLE}")
    endif()
    include_directories(BEFORE ${Vulkan_INCLUDE_DIRS})
    add_subdirectory("${REPO_ROOT}/renderer" "${CMAKE_CURRENT_BINARY_DIR}/renderer")

    add_library(desktop_moon_renderer SHARED desktop_jni.cpp desktop_bridge_win32.cpp "${MOON_RENDER_THREAD}")
    target_compile_definitions(desktop_moon_renderer PRIVATE
        VK_USE_PLATFORM_WIN32_KHR NOMINMAX WIN32_LEAN_AND_MEAN _CRT_SECURE_NO_WARNINGS)
    target_include_directories(desktop_moon_renderer PRIVATE "${JAVA_HOME}/include/win32")
    target_link_libraries(desktop_moon_renderer PRIVATE Vulkan::Vulkan "${JAVA_HOME}/lib/jawt.lib")
    if(MSVC)
        target_compile_options(desktop_moon_renderer PRIVATE /W4 /permissive- /EHsc)
        # Delay-load the Vulkan loader so the DLL (and the app's fallback UI) still loads on
        # machines without a Vulkan driver; desktop_bridge_win32.cpp probes for it first.
        target_link_options(desktop_moon_renderer PRIVATE "/DELAYLOAD:vulkan-1.dll")
        target_link_libraries(desktop_moon_renderer PRIVATE delayimp)
    endif()
    # A fixed location for every generator: Visual Studio would otherwise add Release/.
    set_target_properties(desktop_moon_renderer PROPERTIES
        PREFIX ""
        RUNTIME_OUTPUT_DIRECTORY "$<1:${CMAKE_BINARY_DIR}>")
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    # Ubuntu/Debian: apt install libvulkan-dev libx11-dev glslc (headers, loader, compiler).
    # A driver provides the runtime: the GPU vendor's, or mesa-vulkan-drivers (lavapipe = CPU).
    find_package(Vulkan REQUIRED)
    find_package(X11 REQUIRED)
    find_package(Threads REQUIRED)
    if(NOT EXISTS "${GLSLC_EXECUTABLE}" AND Vulkan_GLSLC_EXECUTABLE)
        set(GLSLC_EXECUTABLE "${Vulkan_GLSLC_EXECUTABLE}")
    endif()
    include_directories(BEFORE ${Vulkan_INCLUDE_DIRS})
    add_subdirectory("${REPO_ROOT}/renderer" "${CMAKE_CURRENT_BINARY_DIR}/renderer")

    add_library(desktop_moon_renderer SHARED desktop_jni.cpp desktop_bridge_linux.cpp "${MOON_RENDER_THREAD}")
    target_compile_definitions(desktop_moon_renderer PRIVATE VK_USE_PLATFORM_XLIB_KHR)
    target_compile_options(desktop_moon_renderer PRIVATE -Wall -Wextra -Wno-missing-field-initializers)
    target_include_directories(desktop_moon_renderer PRIVATE "${JAVA_HOME}/include/linux" ${X11_INCLUDE_DIR})
    # libjawt.so is found through the running JDK's lib directory (the Kotlin side also loads
    # that JDK's jawt first, so the already-loaded copy is reused).
    target_link_libraries(desktop_moon_renderer PRIVATE
        Vulkan::Vulkan ${X11_LIBRARIES} Threads::Threads "${JAVA_HOME}/lib/libjawt.so")
    set_target_properties(desktop_moon_renderer PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
        BUILD_RPATH "${JAVA_HOME}/lib")
else()
    message(FATAL_ERROR "The desktop Vulkan bridge supports macOS, Windows, and Linux only")
endif()

target_compile_features(desktop_moon_renderer PRIVATE cxx_std_20)
target_include_directories(desktop_moon_renderer PRIVATE
    "${REPO_ROOT}/renderer/thread"
    "${JAVA_HOME}/include")
target_link_libraries(desktop_moon_renderer PRIVATE moon_renderer_core)

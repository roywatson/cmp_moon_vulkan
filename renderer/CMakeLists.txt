# Platform-neutral Vulkan Moon renderer. Platform builds add this directory, provide
# GLSLC_EXECUTABLE and a Vulkan include path, then link `moon_renderer_core`.
if(NOT GLSLC_EXECUTABLE OR NOT EXISTS "${GLSLC_EXECUTABLE}")
    message(FATAL_ERROR "GLSLC_EXECUTABLE must point at glslc (found: '${GLSLC_EXECUTABLE}')")
endif()

set(MOON_SHADER_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/shaders")
set(MOON_SHADER_OUTPUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
set(MOON_SHADER_HEADERS "")

function(add_embedded_shader name source)
    set(spv "${MOON_SHADER_OUTPUT_DIR}/${name}.spv")
    set(header "${MOON_SHADER_OUTPUT_DIR}/${name}.spv.h")
    add_custom_command(
        OUTPUT "${header}"
        COMMAND ${CMAKE_COMMAND} -E make_directory "${MOON_SHADER_OUTPUT_DIR}"
        COMMAND "${GLSLC_EXECUTABLE}" --target-env=vulkan1.0 -o "${spv}" "${source}"
        COMMAND ${CMAKE_COMMAND} "-DINPUT=${spv}" "-DOUTPUT=${header}" "-DNAME=${name}" -P "${CMAKE_CURRENT_LIST_DIR}/cmake/embed_spirv.cmake"
        DEPENDS "${source}" "${CMAKE_CURRENT_LIST_DIR}/cmake/embed_spirv.cmake"
        VERBATIM)
    list(APPEND MOON_SHADER_HEADERS "${header}")
    set(MOON_SHADER_HEADERS "${MOON_SHADER_HEADERS}" PARENT_SCOPE)
endfunction()

add_embedded_shader(moon_vert "${MOON_SHADER_SOURCE_DIR}/moon.vert")
add_embedded_shader(moon_frag "${MOON_SHADER_SOURCE_DIR}/moon.frag")
add_custom_target(moon_shaders DEPENDS ${MOON_SHADER_HEADERS})

add_library(moon_renderer_core STATIC src/moon_renderer.cpp src/moon_log.cpp)
add_dependencies(moon_renderer_core moon_shaders)
set_target_properties(moon_renderer_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_features(moon_renderer_core PUBLIC cxx_std_20)
if(MSVC)
    # Warnings stay non-fatal under MSVC: the -Werror baseline is enforced by the clang builds.
    target_compile_options(moon_renderer_core PRIVATE /W4 /permissive-)
else()
    target_compile_options(moon_renderer_core PRIVATE -Wall -Wextra -Werror -Wno-missing-field-initializers)
endif()
if(ANDROID)
    target_link_libraries(moon_renderer_core PUBLIC log)  # moon_log.cpp writes to logcat.
endif()
target_include_directories(moon_renderer_core
    PUBLIC "${CMAKE_CURRENT_LIST_DIR}/include"
    PRIVATE "${MOON_SHADER_OUTPUT_DIR}")

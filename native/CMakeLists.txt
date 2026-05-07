cmake_minimum_required(VERSION 3.18)

project(video_oven_native C CXX)

if (POLICY CMP0068)
    cmake_policy(SET CMP0068 NEW)
endif()
set(CMAKE_POLICY_DEFAULT_CMP0068 NEW)

set(VIDEO_OVEN_CUDA "AUTO" CACHE STRING "CUDA policy: AUTO, ON, or OFF")
set_property(CACHE VIDEO_OVEN_CUDA PROPERTY STRINGS AUTO ON OFF)

set(VIDEO_OVEN_METAL "AUTO" CACHE STRING "Metal policy: AUTO, ON, or OFF")
set_property(CACHE VIDEO_OVEN_METAL PROPERTY STRINGS AUTO ON OFF)

set(VIDEO_OVEN_NATIVE_DIR
    "${CMAKE_CURRENT_LIST_DIR}/../src/main/resources/native"
    CACHE PATH "Directory where native libraries are bundled for the JAR"
)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib")
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")

function(video_oven_normalize_policy name output)
    string(TOUPPER "${${name}}" normalized)
    if (NOT normalized MATCHES "^(AUTO|ON|OFF)$")
        message(FATAL_ERROR "${name} must be AUTO, ON, or OFF, but was: ${${name}}")
    endif()
    set(${output} "${normalized}" PARENT_SCOPE)
endfunction()

video_oven_normalize_policy(VIDEO_OVEN_CUDA VIDEO_OVEN_CUDA_POLICY)
video_oven_normalize_policy(VIDEO_OVEN_METAL VIDEO_OVEN_METAL_POLICY)

function(video_oven_detect_cuda mode output)
    find_package(CUDAToolkit ${mode})
    if (CUDAToolkit_FOUND AND CUDAToolkit_NVCC_EXECUTABLE)
        set(${output} ON PARENT_SCOPE)
    elseif (CUDAToolkit_FOUND AND mode STREQUAL "REQUIRED")
        message(FATAL_ERROR "CUDA Toolkit was found, but nvcc was not found")
    elseif (CUDAToolkit_FOUND)
        message(STATUS "CUDA Toolkit was found without nvcc; disabling CUDA backend")
        set(${output} OFF PARENT_SCOPE)
    else()
        set(${output} OFF PARENT_SCOPE)
    endif()
endfunction()

set(VIDEO_OVEN_ENABLE_CUDA OFF)
if (VIDEO_OVEN_CUDA_POLICY STREQUAL "ON")
    if (NOT CMAKE_SYSTEM_NAME STREQUAL "Linux")
        message(FATAL_ERROR "VIDEO_OVEN_CUDA=ON is supported only on Linux")
    endif()
    video_oven_detect_cuda(REQUIRED VIDEO_OVEN_ENABLE_CUDA)
elseif (VIDEO_OVEN_CUDA_POLICY STREQUAL "AUTO" AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
    video_oven_detect_cuda(QUIET VIDEO_OVEN_ENABLE_CUDA)
endif()

set(VIDEO_OVEN_ENABLE_METAL OFF)
if (VIDEO_OVEN_METAL_POLICY STREQUAL "ON")
    if (NOT APPLE)
        message(FATAL_ERROR "VIDEO_OVEN_METAL=ON is supported only on Apple platforms")
    endif()
    set(VIDEO_OVEN_ENABLE_METAL ON)
elseif (VIDEO_OVEN_METAL_POLICY STREQUAL "AUTO" AND APPLE)
    set(VIDEO_OVEN_ENABLE_METAL ON)
endif()

message(STATUS "VIDEO_OVEN_CUDA=${VIDEO_OVEN_CUDA_POLICY} -> GGML_CUDA=${VIDEO_OVEN_ENABLE_CUDA}")
message(STATUS "VIDEO_OVEN_METAL=${VIDEO_OVEN_METAL_POLICY} -> GGML_METAL=${VIDEO_OVEN_ENABLE_METAL}")

set(BUILD_SHARED_LIBS ON CACHE BOOL "Build shared native libraries" FORCE)
set(GGML_CUDA ${VIDEO_OVEN_ENABLE_CUDA} CACHE BOOL "Enable ggml CUDA backend" FORCE)
set(GGML_METAL ${VIDEO_OVEN_ENABLE_METAL} CACHE BOOL "Enable ggml Metal backend" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "Do not build whisper.cpp tests" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "Do not build whisper.cpp examples" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "Do not build whisper.cpp server" FORCE)

add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../whisper.cpp" "${CMAKE_BINARY_DIR}/whisper.cpp")

add_library(whisper_bridge SHARED whisper_bridge.c)
target_include_directories(whisper_bridge PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../whisper.cpp/include"
    "${CMAKE_CURRENT_LIST_DIR}/../whisper.cpp/ggml/include"
)
target_link_libraries(whisper_bridge PRIVATE whisper)
set_target_properties(whisper_bridge PROPERTIES OUTPUT_NAME whisper_bridge)

if (APPLE)
    set(VIDEO_OVEN_ORIGIN_RPATH "@loader_path")
else()
    set(VIDEO_OVEN_ORIGIN_RPATH "$ORIGIN")
endif()

function(video_oven_configure_runtime_path target_name)
    if (TARGET ${target_name})
        set_target_properties(${target_name} PROPERTIES
            BUILD_WITH_INSTALL_RPATH ON
            INSTALL_RPATH "${VIDEO_OVEN_ORIGIN_RPATH}"
        )
    endif()
endfunction()

set(VIDEO_OVEN_NATIVE_TARGETS
    ggml-base
    ggml-cpu
    ggml
    whisper
    whisper_bridge
)

foreach (optional_target IN ITEMS ggml-blas ggml-metal ggml-cuda)
    if (TARGET ${optional_target})
        list(APPEND VIDEO_OVEN_NATIVE_TARGETS ${optional_target})
    endif()
endforeach()

foreach (native_target IN LISTS VIDEO_OVEN_NATIVE_TARGETS)
    video_oven_configure_runtime_path(${native_target})
endforeach()

set(VIDEO_OVEN_NATIVE_LIBS_CONTENT "set(VIDEO_OVEN_NATIVE_LIBS\n")
foreach (native_target IN LISTS VIDEO_OVEN_NATIVE_TARGETS)
    string(APPEND VIDEO_OVEN_NATIVE_LIBS_CONTENT "    \"$<TARGET_FILE:${native_target}>\"\n")
    string(APPEND VIDEO_OVEN_NATIVE_LIBS_CONTENT "    \"$<TARGET_LINKER_FILE:${native_target}>\"\n")

    get_target_property(native_target_soversion ${native_target} SOVERSION)
    if (NOT "${native_target_soversion}" STREQUAL "native_target_soversion-NOTFOUND")
        string(APPEND VIDEO_OVEN_NATIVE_LIBS_CONTENT "    \"$<TARGET_SONAME_FILE:${native_target}>\"\n")
    endif()
endforeach()
string(APPEND VIDEO_OVEN_NATIVE_LIBS_CONTENT ")\n")

set(VIDEO_OVEN_NATIVE_LIBS_FILE "${CMAKE_CURRENT_BINARY_DIR}/video_oven_native_libs.cmake")
file(GENERATE
    OUTPUT "${VIDEO_OVEN_NATIVE_LIBS_FILE}"
    CONTENT "${VIDEO_OVEN_NATIVE_LIBS_CONTENT}"
)

add_custom_target(video_oven_native_prepare
    COMMAND "${CMAKE_COMMAND}"
            -DVIDEO_OVEN_NATIVE_DIR="${VIDEO_OVEN_NATIVE_DIR}"
            -P "${CMAKE_CURRENT_LIST_DIR}/clean_native_dir.cmake"
)

add_custom_target(video_oven_native_bundle ALL
    COMMAND "${CMAKE_COMMAND}"
            -DVIDEO_OVEN_NATIVE_DIR="${VIDEO_OVEN_NATIVE_DIR}"
            -DVIDEO_OVEN_NATIVE_LIBS_FILE="${VIDEO_OVEN_NATIVE_LIBS_FILE}"
            -P "${CMAKE_CURRENT_LIST_DIR}/bundle_native_libs.cmake"
    DEPENDS video_oven_native_prepare ${VIDEO_OVEN_NATIVE_TARGETS}
)

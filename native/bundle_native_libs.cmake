if (NOT DEFINED VIDEO_OVEN_NATIVE_DIR)
    message(FATAL_ERROR "VIDEO_OVEN_NATIVE_DIR is required")
endif()

if (NOT DEFINED VIDEO_OVEN_NATIVE_LIBS_FILE)
    message(FATAL_ERROR "VIDEO_OVEN_NATIVE_LIBS_FILE is required")
endif()

include("${VIDEO_OVEN_NATIVE_LIBS_FILE}")

file(MAKE_DIRECTORY "${VIDEO_OVEN_NATIVE_DIR}")

set(VIDEO_OVEN_COPIED_NATIVE_LIBS)
foreach (native_lib IN LISTS VIDEO_OVEN_NATIVE_LIBS)
    if (EXISTS "${native_lib}")
        get_filename_component(native_lib_name "${native_lib}" NAME)
        execute_process(
            COMMAND "${CMAKE_COMMAND}" -E copy_if_different
                    "${native_lib}" "${VIDEO_OVEN_NATIVE_DIR}/${native_lib_name}"
            RESULT_VARIABLE copy_result
        )
        if (NOT copy_result EQUAL 0)
            message(FATAL_ERROR "Failed to copy native library: ${native_lib}")
        endif()
        list(APPEND VIDEO_OVEN_COPIED_NATIVE_LIBS "${native_lib_name}")
    endif()
endforeach()

list(REMOVE_DUPLICATES VIDEO_OVEN_COPIED_NATIVE_LIBS)
list(JOIN VIDEO_OVEN_COPIED_NATIVE_LIBS "\n" VIDEO_OVEN_NATIVE_MANIFEST)
file(WRITE "${VIDEO_OVEN_NATIVE_DIR}/libs.txt" "${VIDEO_OVEN_NATIVE_MANIFEST}\n")

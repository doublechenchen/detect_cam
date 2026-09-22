cmake_minimum_required(VERSION 3.22.1)
project(smartcam_rknn)

# Configure with -DRKNN_SDK_DIR=/path/to/rknpu2/runtime/RK3588/Android
if(NOT RKNN_SDK_DIR)
    message(FATAL_ERROR "Set RKNN_SDK_DIR to the RK3588 RKNN Runtime headers/libs")
endif()

add_library(smartcam_rknn SHARED rknn_bridge.cpp)
# Camera preprocessing loops run on every detector and embedding call. Android
# debug builds otherwise compile them at -O0. Preserve IEEE checks (no fast-math).
target_compile_options(smartcam_rknn PRIVATE -O3)
add_library(rknnrt SHARED IMPORTED)
set_target_properties(rknnrt PROPERTIES IMPORTED_LOCATION
        "${RKNN_SDK_DIR}/librknn_api/${CMAKE_ANDROID_ARCH_ABI}/librknnrt.so")
target_include_directories(smartcam_rknn PRIVATE "${RKNN_SDK_DIR}/librknn_api/include")
target_link_libraries(smartcam_rknn rknnrt log)

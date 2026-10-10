cmake_minimum_required(VERSION 3.22.1)
project(qairt_sepformer)

if(NOT DEFINED QAIRT_SDK_ROOT)
    message(FATAL_ERROR "Set QAIRT_SDK_ROOT to the local Qualcomm AI Runtime SDK directory.")
endif()

add_library(qairt_sepformer SHARED qairt_sepformer_jni.cpp)
target_compile_features(qairt_sepformer PRIVATE cxx_std_17)
target_include_directories(qairt_sepformer PRIVATE "${QAIRT_SDK_ROOT}/include/QAIRT")
add_library(qairt_system SHARED IMPORTED)
set_target_properties(qairt_system PROPERTIES
    IMPORTED_LOCATION "${QAIRT_SDK_ROOT}/lib/aarch64-android/libQairtSystem.so")
target_link_libraries(qairt_sepformer qairt_system android log dl)

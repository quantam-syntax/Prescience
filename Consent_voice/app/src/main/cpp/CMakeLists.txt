cmake_minimum_required(VERSION 3.22.1)
project(qairt_sepformer)

set(QAIRT_HEADER "${QAIRT_SDK_ROOT}/include/QAIRT/QairtCpp/QairtApi.hpp")
if(EXISTS "${QAIRT_HEADER}")
    add_library(qairt_sepformer SHARED qairt_sepformer_jni.cpp)
else()
    # Normal source builds do not carry the proprietary QAIRT SDK. Build a
    # JNI stub so the app remains installable and reports HTP unavailable.
    add_library(qairt_sepformer SHARED qairt_sepformer_stub.cpp)
endif()
target_compile_features(qairt_sepformer PRIVATE cxx_std_17)
if(EXISTS "${QAIRT_HEADER}")
    target_include_directories(qairt_sepformer PRIVATE "${QAIRT_SDK_ROOT}/include/QAIRT")
    add_library(qairt_system SHARED IMPORTED)
    set_target_properties(qairt_system PROPERTIES
        IMPORTED_LOCATION "${QAIRT_SDK_ROOT}/lib/aarch64-android/libQairtSystem.so")
    target_link_libraries(qairt_sepformer qairt_system android log dl)
else()
    target_link_libraries(qairt_sepformer android log)
endif()

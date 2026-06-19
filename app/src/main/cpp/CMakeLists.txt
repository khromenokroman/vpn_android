cmake_minimum_required(VERSION 3.22.1)

project("vpn")

set(LIBSODIUM_ROOT
        ${CMAKE_SOURCE_DIR}/../../../../third_party/libsodium-android/${ANDROID_ABI}
)

add_library(sodium STATIC IMPORTED)

set_target_properties(sodium PROPERTIES
        IMPORTED_LOCATION
        ${LIBSODIUM_ROOT}/lib/libsodium.a
)

add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp
        crypto.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME}
        PRIVATE
        ${LIBSODIUM_ROOT}/include
)

target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log
        sodium
)
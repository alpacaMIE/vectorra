cmake_minimum_required(VERSION 3.22)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(VCPKG_TARGET_TRIPLET "arm64-android" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(VCPKG_TARGET_TRIPLET "x64-android" CACHE STRING "" FORCE)
else()
    message(FATAL_ERROR "Unsupported Vectorra SDK ABI: ${ANDROID_ABI}")
endif()

get_filename_component(WORKTREE_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
set(VCPKG_INSTALLED_DIR "${WORKTREE_ROOT}/build/vcpkg_installed_ndk28" CACHE PATH "" FORCE)
set(VCPKG_MANIFEST_MODE OFF CACHE BOOL "" FORCE)

project(vectorra_android_bridge LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ROCKY_SUPPORTS_GDAL OFF CACHE BOOL "" FORCE)
set(ROCKY_SUPPORTS_MBTILES OFF CACHE BOOL "" FORCE)
set(ROCKY_SUPPORTS_AZURE OFF CACHE BOOL "" FORCE)
set(ROCKY_SUPPORTS_IMGUI ON CACHE BOOL "" FORCE)
set(ROCKY_SUPPORTS_HTTPS OFF CACHE BOOL "" FORCE)
set(ROCKY_SUPPORTS_HTTPLIB OFF CACHE BOOL "" FORCE)
set(ROCKY_SUPPORTS_CURL ON CACHE BOOL "" FORCE)
set(ROCKY_SUPPORTS_QT OFF CACHE BOOL "" FORCE)
set(ROCKY_BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(ROCKY_BUILD_APPS OFF CACHE BOOL "" FORCE)
set(ROCKY_BUILD_TESTS OFF CACHE BOOL "" FORCE)

add_subdirectory("${WORKTREE_ROOT}/third_party/rocky" "${CMAKE_CURRENT_BINARY_DIR}/rocky" EXCLUDE_FROM_ALL)

if(ANDROID)
    set(VECTORRA_ANDROID_PAGE_SIZE_LINK_OPTIONS "-Wl,-z,max-page-size=16384")
    target_link_options(rocky PRIVATE ${VECTORRA_ANDROID_PAGE_SIZE_LINK_OPTIONS})
endif()

find_library(TURBOJPEG_LIBRARY
    NAMES turbojpeg
    PATHS
        "${VCPKG_INSTALLED_DIR}/${VCPKG_TARGET_TRIPLET}/lib"
        "${VCPKG_INSTALLED_DIR}/${VCPKG_TARGET_TRIPLET}/debug/lib"
    NO_DEFAULT_PATH
)

if(TURBOJPEG_LIBRARY)
    target_compile_definitions(rocky PRIVATE ROCKY_ANDROID_TURBOJPEG=1)
    target_include_directories(rocky PRIVATE "${VCPKG_INSTALLED_DIR}/${VCPKG_TARGET_TRIPLET}/include")
    target_link_libraries(rocky PRIVATE "${TURBOJPEG_LIBRARY}")
endif()

add_library(vectorra_jni SHARED vectorra_jni.cpp)

if(VECTORRA_ANDROID_PAGE_SIZE_LINK_OPTIONS)
    target_link_options(vectorra_jni PRIVATE ${VECTORRA_ANDROID_PAGE_SIZE_LINK_OPTIONS})
endif()

target_include_directories(vectorra_jni
    PRIVATE
        "${WORKTREE_ROOT}/third_party/rocky/src"
        "${CMAKE_CURRENT_BINARY_DIR}/rocky/build_include"
)

target_link_libraries(vectorra_jni
    PRIVATE
        rocky
        android
        log
        vulkan
)

if(ANDROID)
    target_link_libraries(rocky PRIVATE z)
endif()

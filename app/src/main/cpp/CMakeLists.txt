cmake_minimum_required(VERSION 3.22.1)
project("ds_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# LSPlt is built from a pinned upstream submodule instead of the lsplt:2.1
# prefab: 2.1 predates upstream's 16 KB page-size fix. Kept as its own shared
# library (liblsplt.so), same as the prefab shipped it.
set(LSPLT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/external/lsplt/lsplt/src/main/jni)
if (NOT EXISTS ${LSPLT_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "LSPlt submodule missing; run: git submodule update --init")
endif ()
# Upstream's post-build step writes split debug symbols here; it must be set
# before add_subdirectory or the step targets "/<abi>".
set(DEBUG_SYMBOLS_PATH ${CMAKE_CURRENT_BINARY_DIR}/lsplt-symbols)
add_subdirectory(${LSPLT_DIR} lsplt)
set_target_properties(lsplt PROPERTIES CXX_STANDARD 20)

add_library(ds_native SHARED
    native_hooks.cpp
    property_hooks.cpp
    system_hooks.cpp
)

target_link_libraries(ds_native
    PRIVATE
    lsplt
    log
)

target_compile_options(ds_native PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -ffunction-sections
    -fdata-sections
)

target_link_options(ds_native PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
)

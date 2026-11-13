cmake_minimum_required(VERSION 3.21)
project(launcher_wasi C CXX)

include(${QT2WASM_ROOT}/cmake/Qt2Wasm.cmake)

set(FONTS_QRC_CPP "${CMAKE_CURRENT_BINARY_DIR}/qrc_fonts.cpp")
add_custom_command(
    OUTPUT "${FONTS_QRC_CPP}"
    COMMAND "${QT2WASM_QT_PREFIX}/../host/libexec/rcc"
        --name fonts
        -o "${FONTS_QRC_CPP}"
        "${CMAKE_CURRENT_SOURCE_DIR}/fonts.qrc"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/fonts.qrc"
            "${CMAKE_CURRENT_SOURCE_DIR}/fonts/DejaVuSans.ttf"
            "${CMAKE_CURRENT_SOURCE_DIR}/fonts/DejaVuSans-Bold.ttf"
    COMMENT "Compiling fonts.qrc"
)

qt2wasm_add_component(launcher GUI
    SOURCES
        src/main.cpp
        src/launcher_window.cpp
        src/layout.cpp
        "${FONTS_QRC_CPP}"
    INCLUDE_DIRS
        src
)

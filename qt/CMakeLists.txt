cmake_minimum_required(VERSION 3.21)
project(ultrahome VERSION 0.2 LANGUAGES CXX)

# The ultra launcher, as a Qt Quick app. qt/qml is the UI; qt/src is a thin C++ backend
# over HomeActivity.java (qt/android), which does what only Android can.
#   desktop:  cmake -S qt -B build && cmake --build build && build/ultrahome
#   android:  qt-cmake -S qt -B build -DANDROID_SDK_ROOT=... -DANDROID_NDK_ROOT=...
#             cmake --build build --target apk

set(VERSION_CODE 1 CACHE STRING "Android versionCode (CI passes the workflow run number)")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Qt6 6.8 REQUIRED COMPONENTS Quick)
if(NOT ANDROID)
    find_package(Qt6 6.8 COMPONENTS Test)
endif()
qt_standard_project_setup(REQUIRES 6.8)

# The UI and the backend, as a static QML module both the app and its test link.
qt_add_library(launcher_ui STATIC
    src/backend.cpp src/backend.h
    src/iconprovider.cpp src/iconprovider.h
)
target_include_directories(launcher_ui PUBLIC src)

qt_add_qml_module(launcher_ui
    URI Launcher
    VERSION 1.0
    QML_FILES
        qml/Main.qml
        qml/Layout.js
        qml/AppIcon.qml
        qml/Tile.qml
        qml/Drawer.qml
        qml/Sheet.qml
        qml/SheetButton.qml
        qml/SearchField.qml
        qml/PillButton.qml
    RESOURCES
        demo/apps.json
        demo/widgets.json
)
target_link_libraries(launcher_ui PUBLIC Qt6::Quick)

qt_add_executable(ultrahome src/main.cpp)
target_link_libraries(ultrahome PRIVATE launcher_uiplugin launcher_ui Qt6::Quick)

set_target_properties(ultrahome PROPERTIES
    QT_ANDROID_PACKAGE_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/android"
    QT_ANDROID_PACKAGE_NAME "dev.ultra.launcher"
    QT_ANDROID_VERSION_CODE "${VERSION_CODE}"
    QT_ANDROID_VERSION_NAME "0.2.${VERSION_CODE}"
    QT_ANDROID_MIN_SDK_VERSION 28
    QT_ANDROID_TARGET_SDK_VERSION 35
)

# The end-to-end test (desktop only): QT_QPA_PLATFORM=offscreen build/ultrahome_e2e
if(NOT ANDROID AND TARGET Qt6::Test)
    qt_add_executable(ultrahome_e2e tests/e2e.cpp)
    target_link_libraries(ultrahome_e2e PRIVATE launcher_uiplugin launcher_ui Qt6::Quick Qt6::Test)
endif()

if(NOT ANDROID)
    install(TARGETS ultrahome BUNDLE DESTINATION . RUNTIME DESTINATION bin)
endif()

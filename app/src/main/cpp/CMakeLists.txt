# Copyright (c) Meta Platforms, Inc. and affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

cmake_minimum_required(VERSION 3.22.1)

set(CMAKE_CXX_STANDARD 20)

project("usbvideo")

set(JPEG_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/jniLibs/include)
set(JPEG_LIBRARY ${CMAKE_SOURCE_DIR}/jniLibs/${ANDROID_ABI}/libjpeg.so)
set(JPEG_LIBRARIES ${JPEG_LIBRARY})
set(JPEG_INCLUDE_DIRS ${JPEG_INCLUDE_DIR})

add_library(JPEG SHARED IMPORTED)
set_target_properties(JPEG PROPERTIES
    IMPORTED_LOCATION ${JPEG_LIBRARY}
    INTERFACE_INCLUDE_DIRECTORIES ${JPEG_INCLUDE_DIR}
)

include(FetchContent)

FetchContent_Declare(
        libusb
        GIT_REPOSITORY https://github.com/libusb/libusb-cmake.git
        GIT_TAG 0c424f748fe1e8c055cd645f40171b9d99f71525
)

FetchContent_MakeAvailable(libusb)

FetchContent_Declare(
        libuvc
        GIT_REPOSITORY https://github.com/nano71/libuvc-android.git
        GIT_TAG 7ece0e0f2d12fc3c3b67bf0307284144eb25628d
)

FetchContent_MakeAvailable(libuvc)

FetchContent_Declare(
        libyuv
        GIT_REPOSITORY https://chromium.googlesource.com/libyuv/libyuv
        GIT_TAG eb6e7bb63738e29efd82ea3cf2a115238a89fa51
)

FetchContent_MakeAvailable(libyuv)

if(TARGET yuv_shared)
    target_link_libraries(yuv_shared PRIVATE JPEG)
endif()

add_library(${CMAKE_PROJECT_NAME}
        SHARED
        UsbVideoNativeLibrary.cpp
        UsbAudioStreamer.cpp
        UsbVideoStreamer.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${libyuv_SOURCE_DIR}/include
)

target_link_libraries(${CMAKE_PROJECT_NAME}
        usb-1.0
        uvc
        yuv
        mediandk
        android
        aaudio
        jnigraphics
        log
        GLESv3
)

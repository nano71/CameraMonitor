/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <memory>
#include <string>

#include "UsbAudioStreamer.h"
#include "UsbVideoStreamer.h"
#include "clog.h"

static std::unique_ptr<UsbAudioStreamer> streamer_{};
static std::unique_ptr<UsbVideoStreamer> uvcStreamer_{};

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *jvm, void *reserved) {
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_com_nano71_cameramonitor_core_usb_UsbVideoNativeLibrary_getUsbDeviceSpeed(JNIEnv *env, jobject self) {
    if (streamer_ != nullptr) {
        return streamer_->getUsbDeviceSpeed();
    }
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_nano71_cameramonitor_core_usb_UsbVideoNativeLibrary_connectUsbVideoStreamingNative(
        JNIEnv *env,
        jobject self,
        jint deviceFd,
        jint width,
        jint height,
        jint fps,
        jint libuvcFrameFormat) {
    if (uvcStreamer_ == nullptr) {
        uvcStreamer_ = std::make_unique<UsbVideoStreamer>(
                (intptr_t) deviceFd, width, height, fps, static_cast<uvc_frame_format>(libuvcFrameFormat));
        return uvcStreamer_->configureOutput();
    }
    return false;
}

JNIEXPORT jboolean JNICALL
Java_com_nano71_cameramonitor_core_usb_UsbVideoNativeLibrary_startUsbVideoStreamingNative(
        JNIEnv *env,
        jobject self) {
    if (uvcStreamer_ != nullptr) {
        return uvcStreamer_->start();
    }
    return false;
}

JNIEXPORT void JNICALL Java_com_nano71_cameramonitor_core_usb_UsbVideoNativeLibrary_stopUsbVideoStreamingNative(
        JNIEnv *env,
        jobject self) {
    if (uvcStreamer_ != nullptr) {
        uvcStreamer_->stop();
    }
}

JNIEXPORT void JNICALL Java_com_nano71_cameramonitor_core_usb_UsbVideoNativeLibrary_disconnectUsbVideoStreamingNative(
        JNIEnv *env,
        jobject self) {
    uvcStreamer_ = nullptr;
}

JNIEXPORT jstring JNICALL Java_com_nano71_cameramonitor_core_usb_UsbVideoNativeLibrary_streamingStatsSummaryString(
        JNIEnv *env,
        jobject self) {
    std::string result = "";
    if (uvcStreamer_ != nullptr) {
        result = uvcStreamer_->statsSummaryString();
    }
    return env->NewStringUTF(result.c_str());
}


JNIEXPORT jboolean JNICALL
Java_com_nano71_cameramonitor_core_usb_UsbVideoNativeLibrary_updateTextures(JNIEnv *env, jobject self, jint texY, jint texUV) {
    if (uvcStreamer_) {
        return uvcStreamer_->bindFrameToTextures(texY, texUV);
    }
    return false;
}

JNIEXPORT jint JNICALL
Java_com_nano71_cameramonitor_core_usb_UsbVideoNativeLibrary_getVideoFormat(JNIEnv *env, jobject self) {
    if (uvcStreamer_) {
        return uvcStreamer_->getFormat();
    }
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_nano71_cameramonitor_core_usb_UsbVideoNativeLibrary_connectUsbAudioStreamingNative(
        JNIEnv *env,
        jobject self,
        jint deviceFd,
        jint jAudioFormat,
        jint samplingFrequency,
        jint subFrameSize,
        jint channelCount,
        jint jAudioPerfMode,
        jint outputFramesPerBuffer) {
    if (streamer_ != nullptr) return true;
    streamer_ = std::make_unique<UsbAudioStreamer>(
            (intptr_t) deviceFd,
            jAudioFormat,
            samplingFrequency,
            subFrameSize,
            channelCount,
            jAudioPerfMode,
            outputFramesPerBuffer);
    return streamer_ != nullptr;
}

JNIEXPORT void JNICALL Java_com_nano71_cameramonitor_core_usb_UsbVideoNativeLibrary_disconnectUsbAudioStreamingNative(
        _JNIEnv *env,
        jobject self) {
    streamer_ = nullptr;
}

JNIEXPORT void JNICALL Java_com_nano71_cameramonitor_core_usb_UsbVideoNativeLibrary_startUsbAudioStreamingNative(
        JNIEnv *env,
        jobject self) {
    if (streamer_ != nullptr) streamer_->start();
}

JNIEXPORT void JNICALL Java_com_nano71_cameramonitor_core_usb_UsbVideoNativeLibrary_stopUsbAudioStreamingNative(
        JNIEnv *env,
        jobject self) {
    if (streamer_ != nullptr) streamer_->stop();
}

} // extern "C"

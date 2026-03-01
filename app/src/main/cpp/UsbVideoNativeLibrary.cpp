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
#include <memory.h>
#include <string>

#include "UsbAudioStreamer.h"
#include "UsbVideoStreamer.h"
#include "clog.h"

JavaVM *g_javaVM = nullptr;
jclass g_zebraClass = nullptr;
jmethodID g_zebraMethod = nullptr;

static std::unique_ptr<UsbAudioStreamer> streamer_{};
static std::unique_ptr<UsbVideoStreamer> uvcStreamer_{};

using ANativeWindowOwner = std::unique_ptr<ANativeWindow, decltype(&ANativeWindow_release)>;
static ANativeWindowOwner previewWindow_ = ANativeWindowOwner(nullptr, &ANativeWindow_release);

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *jvm, void *reserved) {
    g_javaVM = jvm;
    JNIEnv *env;
    if (JNI_OK != jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6)) {
        CLOGE("Get JNIEnv failed");
        return JNI_ERR;
    }

    jclass localClass = env->FindClass("com/nano71/cameramonitor/core/usb/UsbVideoNativeLibrary");
    if (localClass) {
        g_zebraClass = (jclass) env->NewGlobalRef(localClass);
        g_zebraMethod = env->GetStaticMethodID(g_zebraClass, "applyDynamicZebra", "(Ljava/nio/ByteBuffer;IIIJ)V");
    }

    CLOGI("JNI_OnLoad success!");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *jvm, void *reserved) {
    JNIEnv *env;
    if (jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) {
        if (g_zebraClass) {
            env->DeleteGlobalRef(g_zebraClass);
            g_zebraClass = nullptr;
        }
    }
    g_javaVM = nullptr;
    CLOGI("JNI_OnUnload success!");
}

void applyZebraKotlinBridge(uint8_t *pixels, int width, int height, int stride, uint64_t frameCount) {
    if (!g_javaVM || !g_zebraClass || !g_zebraMethod) return;

    JNIEnv *env;
    jint res = g_javaVM->GetEnv((void **) &env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_javaVM->AttachCurrentThread(&env, nullptr) != 0) {
            CLOGE("Failed to attach thread for zebra");
            return;
        }
    } else if (res != JNI_OK) {
        return;
    }

    jobject byteBuffer = env->NewDirectByteBuffer(pixels, (jlong) stride * height * 4);
    if (byteBuffer) {
        env->CallStaticVoidMethod(g_zebraClass, g_zebraMethod, byteBuffer, width, height, stride, (jlong) frameCount);
        env->DeleteLocalRef(byteBuffer);
    }
}

JNIEXPORT jint JNICALL
Java_com_nano71_cameramonitor_core_usb_UsbVideoNativeLibrary_getUsbDeviceSpeed(JNIEnv *env, jobject self) {
    if (streamer_ != nullptr) {
        return streamer_->getUsbDeviceSpeed();
    }
    return 0; /* LIBUSB_SPEED_UNKNOWN */
}

JNIEXPORT jboolean JNICALL
Java_com_nano71_cameramonitor_core_usb_UsbVideoNativeLibrary_connectUsbVideoStreamingNative(
        JNIEnv *env,
        jobject tis,
        jint deviceFd,
        jint width,
        jint height,
        jint fps,
        jint libuvcFrameFormat,
        jobject jSurface) {
    CLOGE(
            " Java_com_nano71_cameramonitor_core_usb_UsbVideoNativeLibrary__connectUsbVideoStreamingNative called with deviceFd %d",
            deviceFd);
    if (uvcStreamer_ == nullptr) {
        uvcStreamer_ = std::make_unique<UsbVideoStreamer>(
                (intptr_t) deviceFd, width, height, fps, static_cast<uvc_frame_format>(libuvcFrameFormat));
        previewWindow_.reset(ANativeWindow_fromSurface(env, jSurface));
        return uvcStreamer_->configureOutput(previewWindow_.get());
    }
    return false;
}

JNIEXPORT void JNICALL Java_com_nano71_cameramonitor_core_usb_UsbVideoNativeLibrary_setZebraVisible(JNIEnv *env, jobject, jboolean visible) {
    if (uvcStreamer_) uvcStreamer_->setZebraVisible(visible);
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
    previewWindow_.reset(nullptr);
}

JNIEXPORT jstring JNICALL Java_com_nano71_cameramonitor_core_usb_UsbVideoNativeLibrary_streamingStatsSummaryString(
        JNIEnv *env,
        jobject self) {
    std::string result = "";
    if (streamer_ != nullptr) {
        result += streamer_->statsSummaryString();
        result += "\n";
    }
    if (uvcStreamer_ != nullptr) {
        result += uvcStreamer_->statsSummaryString();
    }
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_nano71_cameramonitor_core_usb_UsbVideoNativeLibrary_connectUsbAudioStreamingNative(
        JNIEnv *env,
        jobject tis,
        jint deviceFd,
        jint jAudioFormat,
        jint samplingFrequency,
        jint subFrameSize,
        jint channelCount,
        jint jAudioPerfMode,
        jint outputFramesPerBuffer) {
    if (streamer_ != nullptr) {
        //CLOGE("startUsbAudioStreamingNative called before stopUsbAudioStreamingNative was called");
        return true;
    }
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
        JNIEnv *env,
        jobject self) {
    if (streamer_ != nullptr) {
        streamer_ = nullptr;
    }
}
JNIEXPORT void JNICALL Java_com_nano71_cameramonitor_core_usb_UsbVideoNativeLibrary_startUsbAudioStreamingNative(
        JNIEnv *env,
        jobject self) {
    if (streamer_ != nullptr) {
        streamer_->start();
    }
}

JNIEXPORT void JNICALL Java_com_nano71_cameramonitor_core_usb_UsbVideoNativeLibrary_stopUsbAudioStreamingNative(
        JNIEnv *env,
        jobject self) {
    if (streamer_ != nullptr) {
        streamer_->stop();
    }
}

} // extern "C"

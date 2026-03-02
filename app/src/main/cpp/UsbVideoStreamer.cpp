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

#include "UsbVideoStreamer.h"

#include <android/bitmap.h>
#include <android/data_space.h>

#include <android/log.h>
#include <jni.h>
#include <libusb.h>
#include <libuvc/libuvc.h>
#include <libyuv.h>
#include <libyuv/convert_argb.h>
#include <libyuv/convert_from_argb.h>
#include <libyuv/planar_functions.h>

#include <chrono>
#include <format>
#include <memory.h>
#include <sys/prctl.h>
#include <unistd.h>
#include <cstring>

#define ULOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "UsbVideoStreamer", __VA_ARGS__)
#define ULOGI(...) __android_log_print(ANDROID_LOG_INFO, "UsbVideoStreamer", __VA_ARGS__)
#define ULOGW(...) __android_log_print(ANDROID_LOG_WARN, "UsbVideoStreamer", __VA_ARGS__)
#define ULOGE(...) __android_log_print(ANDROID_LOG_ERROR, "UsbVideoStreamer", __VA_ARGS__)

UsbVideoStreamer::UsbVideoStreamer(
        intptr_t deviceFD,
        int32_t width,
        int32_t height,
        int32_t fps,
        uvc_frame_format uvcFrameFormat) :
        deviceFD_(deviceFD),
        width_(width),
        height_(height),
        fps_(fps),
        uvcFrameFormat_(uvcFrameFormat) {
    if (libusb_set_option(nullptr, LIBUSB_OPTION_WEAK_AUTHORITY) != LIBUSB_SUCCESS) {
        ULOGE("libusb setting no discovery option failed");
    }

    uvc_error_t res = uvc_init(&uvcContext_, nullptr);
    if (res != UVC_SUCCESS) {
        ULOGE("uvc_init failed %s", uvc_strerror(res));
        return;
    }

    if ((uvc_wrap(deviceFD, uvcContext_, &deviceHandle_) != UVC_SUCCESS) ||
        (deviceHandle_ == nullptr)) {
        ULOGE("uvc_wrap error");
        return;
    }

    res = uvc_get_stream_ctrl_format_size(
            deviceHandle_,
            &streamCtrl_,
            uvcFrameFormat_,
            width,
            height,
            fps);
    if (res == UVC_SUCCESS) {
        captureFrameWidth_ = width;
        captureFrameHeight_ = height;
        captureFrameFps_ = fps;
        captureFrameFormat_ = uvcFrameFormat_;
        isStreamControlNegotiated_ = true;

        if (uvcFrameFormat_ == UVC_FRAME_FORMAT_NV12) {
            plane0_.resize(width * height);
            plane1_.resize(width * height / 2);
        } else if (uvcFrameFormat_ == UVC_FRAME_FORMAT_YUYV) {
            plane0_.resize(width * height * 2);
        } else if (uvcFrameFormat_ == UVC_FRAME_FORMAT_MJPEG) {
            plane0_.resize(width * height * 4);
        }
    } else {
        isStreamControlNegotiated_ = false;
        ULOGE("uvc_get_stream_ctrl_format_size failed %s", uvc_strerror(res));
    }
}

bool UsbVideoStreamer::configureOutput() {
    if (!isStreamControlNegotiated_) return false;
    uvc_error_t ret = uvc_stream_open_ctrl(deviceHandle_, &streamHandle_, &streamCtrl_);
    return ret == UVC_SUCCESS;
}

bool UsbVideoStreamer::start() {
    if (streamHandle_ == nullptr) return false;
    uvc_error_t ret = uvc_stream_start(streamHandle_, captureFrameCallback, this, 0);
    return ret == UVC_SUCCESS;
}

bool UsbVideoStreamer::stop() {
    if (streamHandle_ == nullptr) return false;
    return uvc_stream_stop(streamHandle_) == UVC_SUCCESS;
}

std::string UsbVideoStreamer::statsSummaryString() const {
    return std::format("{}x{} @{} fps", captureFrameWidth_, captureFrameHeight_, stats_.fps);
}

UsbVideoStreamer::~UsbVideoStreamer() {
    if (deviceHandle_ != nullptr) uvc_close(deviceHandle_);
    if (uvcContext_ != nullptr) uvc_exit(uvcContext_);
}

int UsbVideoStreamer::getFormat() const {
    switch (captureFrameFormat_) {
        case UVC_FRAME_FORMAT_NV12:
            return 1;
        case UVC_FRAME_FORMAT_YUYV:
            return 2;
        default:
            return 0;
    }
}

bool UsbVideoStreamer::bindFrameToTextures(int texY, int texUV) {
    std::lock_guard<std::mutex> lock(frameMutex_);
    if (!frameUpdated_) return false;

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texY);

    if (getFormat() == 1) { // NV12
        // In GLES 3.0, use GL_R8 and GL_RED for the Y plane
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, width_, height_, 0, GL_RED, GL_UNSIGNED_BYTE, plane0_.data());

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, texUV);
        // In GLES 3.0, use GL_RG8 and GL_RG for the UV plane
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RG8, width_ / 2, height_ / 2, 0, GL_RG, GL_UNSIGNED_BYTE, plane1_.data());
    } else if (getFormat() == 2) { // YUYV
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width_ / 2, height_, 0, GL_RGBA, GL_UNSIGNED_BYTE, plane0_.data());
    } else { // RGBA (MJPEG)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width_, height_, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgbaBuffer_.data());
    }

    frameUpdated_ = false;
    return true;
}


void UsbVideoStreamer::captureFrameCallback(uvc_frame_t *frame, void *user_data) {
    UsbVideoStreamer *self = (UsbVideoStreamer *) user_data;
    UsbVideoStreamerStats &stats = self->stats_;

    std::lock_guard<std::mutex> lock(self->frameMutex_);
    int width = frame->width;
    int height = frame->height;
    self->width_ = width;
    self->height_ = height;

    if (self->rgbaBuffer_.size() != width * height * 4) {
        self->rgbaBuffer_.resize(width * height * 4);
    }
    uint8_t *rgbaData = self->rgbaBuffer_.data();

    switch (frame->frame_format) {
        case UVC_FRAME_FORMAT_NV12: {
            size_t y_size = width * height;
            size_t uv_size = y_size / 2;
            if (self->plane0_.size() != y_size) self->plane0_.resize(y_size);
            if (self->plane1_.size() != uv_size) self->plane1_.resize(uv_size);
            std::memcpy(self->plane0_.data(), frame->data, y_size);
            std::memcpy(self->plane1_.data(), (uint8_t *) frame->data + y_size, uv_size);
            break;
        }
        case UVC_FRAME_FORMAT_YUYV: {
            size_t size = width * height * 2;
            if (self->plane0_.size() != size) self->plane0_.resize(size);
            std::memcpy(self->plane0_.data(), frame->data, size);
            break;
        }
        case UVC_FRAME_FORMAT_MJPEG: {
            uvc_frame_t *rgb_frame = uvc_allocate_frame(width * height * 3);
            if (rgb_frame) {
                if (uvc_mjpeg2rgb(frame, rgb_frame) == UVC_SUCCESS) {
                    libyuv::RAWToARGB(
                            (uint8_t *) rgb_frame->data, rgb_frame->step,
                            rgbaData, width * 4,
                            width, height);
                    libyuv::ARGBToABGR(rgbaData, width * 4, rgbaData, width * 4, width, height);
                }
                uvc_free_frame(rgb_frame);
            }
            break;
        }
        default:
            break;
    }

    self->frameUpdated_ = true;
    stats.recordFrame();
}

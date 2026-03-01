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

#pragma once

#include <libusb.h>
#include <libuvc/libuvc.h>
#include <jni.h>
#include <GLES2/gl2.h>
#include <chrono>
#include <cstdint>
#include <memory>
#include <vector>
#include <string>
#include <mutex>

using namespace std::chrono;

struct UsbVideoStreamerStats {
    u_int64_t total_bytes = 0;
    uint16_t usb_cb_counter = 0;
    uint16_t frames = 0;
    steady_clock::time_point lastFpsUpdate{0s};
    uint8_t fps = 0;
    uint8_t currentFps = 0;
    steady_clock::time_point t0{high_resolution_clock::now()};

    steady_clock::time_point captureRenderClock_{high_resolution_clock::now()};
    nanoseconds capture_{0ns};
    nanoseconds render_{0ns};

    void recordCapture() {
        auto now = high_resolution_clock::now();
        capture_ += (now - captureRenderClock_);
        captureRenderClock_ = now;
    }

    void recordRender() {
        auto now = high_resolution_clock::now();
        render_ += (now - captureRenderClock_);
        captureRenderClock_ = now;
    }

    void recordFrame() {
        currentFps++;
        auto now = high_resolution_clock::now();
        if (now - t0 >= 1s) {
            t0 = now;
            fps = currentFps;
            currentFps = 0;
        }
    }
};

class UsbVideoStreamer final {
public:
    static void captureFrameCallback(uvc_frame_t *frame, void *user_data);

    void setZebraVisible(jboolean visible);

    UsbVideoStreamer(
            intptr_t deviceFD,
            int32_t width,
            int32_t height,
            int32_t fps,
            uvc_frame_format uvcFrameFormat);

    ~UsbVideoStreamer();

    bool configureOutput();

    bool start();

    bool stop();


    std::string statsSummaryString() const;

    // For Kotlin Renderer
    int getFormat() const;
    bool bindFrameToTextures(int texY, int texUV);

private:
    uvc_context_t *uvcContext_{};
    uvc_device_handle_t *deviceHandle_{};
    uvc_stream_ctrl_t streamCtrl_{};
    bool isStreamControlNegotiated_{false};
    uvc_stream_handle_t *streamHandle_{nullptr};

    intptr_t deviceFD_;
    int32_t width_;
    int32_t height_;
    int32_t fps_;
    uvc_frame_format uvcFrameFormat_;

    int32_t captureFrameWidth_{};
    int32_t captureFrameHeight_{};
    int32_t captureFrameFps_{};
    uvc_frame_format captureFrameFormat_{};

    UsbVideoStreamerStats stats_{};

    std::mutex frameMutex_;
    bool frameUpdated_{false};
    std::vector<uint8_t> plane0_;
    std::vector<uint8_t> plane1_;
    std::vector<uint8_t> rgbaBuffer_;
};

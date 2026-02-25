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
package com.meta.usbvideo.viewModel

import android.app.Application
import android.util.Log
import com.meta.usbvideo.UsbVideoNativeLibrary
import com.meta.usbvideo.eventloop.EventLooper
import com.meta.usbvideo.usb.UsbDeviceState
import com.meta.usbvideo.usb.UsbMonitor
import com.meta.usbvideo.usb.UsbMonitor.setState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combineTransform

private const val TAG = "StreamerUiActionDelegate"

class StreamerUiActionDelegate(
    private val application: Application,
) {
    fun uiActionFlow(streamerViewModel: StreamerViewModel): Flow<UiAction> {
        return combineTransform(
            UsbMonitor.usbDeviceStateFlow,
        ) { usbDeviceState: UsbDeviceState ->
            when {
                usbDeviceState is UsbDeviceState.NotFound -> {
                    Log.i(TAG, "UsbDeviceState NotFound. No op")
                }

                usbDeviceState is UsbDeviceState.PermissionRequired -> {
                    Log.i(TAG, "usb permission required. Will try after few seconds")
                }

                usbDeviceState is UsbDeviceState.PermissionRequested -> {
                    Log.i(TAG, "usb permission requested. Waiting for result")
                }

                usbDeviceState is UsbDeviceState.PermissionGranted -> {
                    streamerViewModel.onUsbPermissionGranted(usbDeviceState.usbDevice)
                }

                usbDeviceState is UsbDeviceState.Attached -> {
                    streamerViewModel.onUsbDeviceAttached(usbDeviceState.usbDevice)
                }

                usbDeviceState is UsbDeviceState.Detached -> {
                    Log.i(TAG, "usbDeviceState is UsbDeviceState.Detached")

                    EventLooper.call {
                        UsbVideoNativeLibrary.stopUsbAudioStreamingNative()
                        UsbVideoNativeLibrary.stopUsbVideoStreamingNative()
                        UsbVideoNativeLibrary.disconnectUsbAudioStreamingNative()
                        UsbVideoNativeLibrary.disconnectUsbVideoStreamingNative()
                        UsbMonitor.disconnect()
                    }
                    emit(DismissStreamingScreen)
                }

                usbDeviceState is UsbDeviceState.Connected -> {
                    Log.i(TAG, "usbDeviceState is UsbDeviceState.Connected")
                    usbDeviceState.videoStreamingConnection.let {
                        streamerViewModel.videoFormats = it.videoFormats
                        streamerViewModel.videoFormat = it.findBestVideoFormat(1920, 1080)
                    }
                    emit(PresentStreamingScreen)
                    val videoStreamingSurface = streamerViewModel.awaitVideoSurface()
                    val (audioStreamStatus, audioStreamMessage) =
                        EventLooper.call {
                            UsbVideoNativeLibrary.connectUsbAudioStreaming(
                                application.applicationContext,
                                usbDeviceState.audioStreamingConnection,
                            ).also {
                                UsbVideoNativeLibrary.startUsbAudioStreamingNative()
                            }
                        }
                    Log.i(TAG, "startUsbAudioStreaming $audioStreamStatus, $audioStreamMessage")
                    val (videoStreamStatus, videoStreamMessage) =
                        EventLooper.call {
                            UsbVideoNativeLibrary.connectUsbVideoStreaming(
                                usbDeviceState.videoStreamingConnection,
                                videoStreamingSurface,
                                streamerViewModel.videoFormat,
                            ).also {
                                UsbVideoNativeLibrary.startUsbVideoStreamingNative()
                            }
                        }
                    Log.i(TAG, "startUsbVideoStreaming $videoStreamStatus, $videoStreamMessage")
                    val streamingState =
                        UsbDeviceState.Streaming(
                            usbDeviceState.usbDevice,
                            usbDeviceState.audioStreamingConnection,
                            audioStreamStatus,
                            audioStreamMessage,
                            usbDeviceState.videoStreamingConnection,
                            videoStreamStatus,
                            videoStreamMessage,
                        )
                    setState(streamingState)
                }

                usbDeviceState is UsbDeviceState.StreamingStop -> {
                    Log.i(TAG, "usbDeviceState is UsbDeviceState.StreamingStop")

                    EventLooper.call {
                        UsbVideoNativeLibrary.stopUsbAudioStreamingNative()
                        UsbVideoNativeLibrary.stopUsbVideoStreamingNative()
                        setState(
                            UsbDeviceState.StreamingStopped(
                                usbDeviceState.usbDevice,
                                usbDeviceState.audioStreamingConnection,
                                usbDeviceState.videoStreamingConnection
                            )
                        )
                    }
                }

                usbDeviceState is UsbDeviceState.StreamingRestart -> {
                    EventLooper.call {
                        UsbVideoNativeLibrary.startUsbAudioStreamingNative()
                        UsbVideoNativeLibrary.startUsbVideoStreamingNative()
                        setState(
                            UsbDeviceState.Streaming(
                                usbDeviceState.usbDevice,
                                usbDeviceState.audioStreamingConnection,
                                true,
                                "Success",
                                usbDeviceState.videoStreamingConnection,
                                true,
                                "Success",
                            )
                        )
                    }
                }
            }
        }
    }
}

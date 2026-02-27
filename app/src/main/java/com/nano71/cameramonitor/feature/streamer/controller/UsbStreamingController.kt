package com.nano71.cameramonitor.feature.streamer.controller

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import android.view.Surface
import com.nano71.cameramonitor.core.connection.VideoFormat
import com.nano71.cameramonitor.core.eventloop.EventLooper
import com.nano71.cameramonitor.core.usb.UsbDeviceState
import com.nano71.cameramonitor.core.usb.UsbMonitor
import com.nano71.cameramonitor.core.usb.UsbVideoNativeLibrary

private const val TAG = "UsbStreamingController"

internal class UsbStreamingController {
    fun connect(device: UsbDevice) {
        Log.i(TAG, "connect() called")
        UsbMonitor.connect(device)
    }

    suspend fun disconnect() {
        Log.i(TAG, "disconnect() called")
        EventLooper.call {
            UsbMonitor.disconnect()
        }
    }

    suspend fun startStreaming(
        context: Context,
        usbDeviceState: UsbDeviceState.Connected,
        surface: Surface,
        videoFormat: VideoFormat
    ): UsbDeviceState.Streaming {
        val (audioStreamStatus, audioStreamMessage) =
            EventLooper.call {
                UsbVideoNativeLibrary.connectUsbAudioStreaming(
                    context,
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
                    surface,
                    videoFormat,
                ).also {
                    UsbVideoNativeLibrary.startUsbVideoStreamingNative()
                }
            }
        Log.i(TAG, "startUsbVideoStreaming $videoStreamStatus, $videoStreamMessage")

        return UsbDeviceState.Streaming(
            usbDeviceState.usbDevice,
            usbDeviceState.audioStreamingConnection,
            audioStreamStatus,
            audioStreamMessage,
            usbDeviceState.videoStreamingConnection,
            videoStreamStatus,
            videoStreamMessage,
        )
    }

    suspend fun stopStreamingNative() {
        EventLooper.call {
            UsbVideoNativeLibrary.stopUsbAudioStreamingNative()
            UsbVideoNativeLibrary.stopUsbVideoStreamingNative()
            UsbVideoNativeLibrary.disconnectUsbAudioStreamingNative()
            UsbVideoNativeLibrary.disconnectUsbVideoStreamingNative()
        }
    }

    suspend fun stopStreaming(
        usbDeviceState: UsbDeviceState.StreamingStop
    ): UsbDeviceState.StreamingStopped {

        return EventLooper.call {
            UsbVideoNativeLibrary.stopUsbAudioStreamingNative()
            UsbVideoNativeLibrary.stopUsbVideoStreamingNative()

            UsbDeviceState.StreamingStopped(
                usbDeviceState.usbDevice,
                usbDeviceState.audioStreamingConnection,
                usbDeviceState.videoStreamingConnection
            )
        }
    }

    suspend fun restartStreaming(
        usbDeviceState: UsbDeviceState.StreamingRestart,
    ): UsbDeviceState.Streaming {
        return EventLooper.call {
            UsbVideoNativeLibrary.startUsbAudioStreamingNative()
            UsbVideoNativeLibrary.startUsbVideoStreamingNative()
            UsbDeviceState.Streaming(
                usbDeviceState.usbDevice,
                usbDeviceState.audioStreamingConnection,
                true,
                "Success",
                usbDeviceState.videoStreamingConnection,
                true,
                "Success",
            )
        }
    }
}
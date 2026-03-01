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
package com.nano71.cameramonitor.feature.streamer

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nano71.cameramonitor.core.connection.VideoFormat
import com.nano71.cameramonitor.core.permission.CameraPermissionRequired
import com.nano71.cameramonitor.core.permission.CameraPermissionState
import com.nano71.cameramonitor.core.permission.PermissionStatus
import com.nano71.cameramonitor.core.permission.RecordAudioPermissionRequired
import com.nano71.cameramonitor.core.permission.RecordAudioPermissionState
import com.nano71.cameramonitor.core.permission.toCameraState
import com.nano71.cameramonitor.core.permission.toRecordAudioState
import com.nano71.cameramonitor.core.usb.UsbDeviceState
import com.nano71.cameramonitor.core.usb.UsbMonitor
import com.nano71.cameramonitor.core.usb.UsbMonitor.findUvcDevice
import com.nano71.cameramonitor.core.usb.UsbMonitor.getUsbManager
import com.nano71.cameramonitor.core.usb.UsbMonitor.setState
import com.nano71.cameramonitor.core.usb.UsbVideoNativeLibrary
import com.nano71.cameramonitor.feature.streamer.controller.UsbStreamingController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn

private const val TAG = "StreamerViewModel"

/** Reactively monitors the state of USB AVC device and implements state transitions methods */
class StreamerViewModel() : ViewModel() {

    var videoFormat: VideoFormat? = null
    var videoFormats: List<VideoFormat> = emptyList()
    private val controller = UsbStreamingController()

    fun stopStreaming() {
        (UsbMonitor.usbDeviceState as? UsbDeviceState.Streaming)?.let {
            setState(
                UsbDeviceState.StreamingStop(
                    it.usbDevice,
                    it.audioStreamingConnection,
                    it.videoStreamingConnection,
                )
            )
        }
    }

    fun restartStreaming() {
        (UsbMonitor.usbDeviceState as? UsbDeviceState.StreamingStopped)?.let {
            setState(
                UsbDeviceState.StreamingRestart(
                    it.usbDevice,
                    it.audioStreamingConnection,
                    it.videoStreamingConnection,
                )
            )
        }
    }

    private val cameraPermissionInternalState: MutableStateFlow<CameraPermissionState> =
        MutableStateFlow(CameraPermissionRequired)
    val cameraPermissionStateFlow: StateFlow<CameraPermissionState> =
        cameraPermissionInternalState.asStateFlow()

    private val recordAudioPermissionInternalState: MutableStateFlow<RecordAudioPermissionState> =
        MutableStateFlow(RecordAudioPermissionRequired)
    val recordAudioPermissionStateFlow: StateFlow<RecordAudioPermissionState> =
        recordAudioPermissionInternalState.asStateFlow()

    suspend fun onUsbDeviceConnected(context: Context, usbDeviceState: UsbDeviceState.Connected) {
        usbDeviceState.videoStreamingConnection.let {
            videoFormats = it.videoFormats
            videoFormat = it.findBestVideoFormat(1920, 1080)
        }
        val streamingState = controller.startStreaming(
            context,
            usbDeviceState,
            videoFormat!!
        )
        setState(streamingState)
    }

    suspend fun onUsbDeviceDetached(usbDeviceState: UsbDeviceState.Detached) {
        Log.i(TAG, "onUsbDeviceDetached() called")
        controller.stopStreamingNative()
        controller.disconnect()
        setState(usbDeviceState)
    }

    suspend fun onStreamingStopRequested(usbDeviceState: UsbDeviceState.StreamingStop) {
        setState(controller.stopStreaming(usbDeviceState))
    }

    suspend fun onStreamingRestartRequested(usbDeviceState: UsbDeviceState.StreamingRestart) {
        setState(controller.restartStreaming(usbDeviceState))
    }

    fun getVideoStreamInfoString(): String {
        val videoStatsLine =
            UsbVideoNativeLibrary.streamingStatsSummaryString()
                .lineSequence()
                .map { it.trim() }
                .filter { it.contains("x") && it.contains("fps") }
                .lastOrNull()

        if (videoStatsLine != null) {
            return videoStatsLine
        }
        val selectedVideoFormat = videoFormat ?: return ""
        return "${selectedVideoFormat.fourccFormat} ${selectedVideoFormat.width}x${selectedVideoFormat.height} @${selectedVideoFormat.fps} fps"
    }

    fun updateCameraPermissionFromStatus(permissionStatus: PermissionStatus) {
        updateCameraPermissionState(permissionStatus.toCameraState())
    }

    fun updateRecordAudioPermissionFromStatus(permissionStatus: PermissionStatus) {
        updateRecordAudioPermissionState(permissionStatus.toRecordAudioState())
    }


    fun updateCameraPermissionState(cameraPermission: CameraPermissionState) {
        Log.i(TAG, "updateCameraPermissionState to $cameraPermission")
        cameraPermissionInternalState.value = cameraPermission
    }

    fun updateRecordAudioPermissionState(recordAudioPermission: RecordAudioPermissionState) {
        Log.i(TAG, "recordAudioPermission set to $recordAudioPermission")
        recordAudioPermissionInternalState.value = recordAudioPermission
    }


    fun onUsbDeviceAttached(usbDevice: UsbDevice) {
        val deviceState = UsbMonitor.usbDeviceState
        if (deviceState is UsbDeviceState.Connected) {
            Log.i(TAG, "Device is already connected. Ignoring onUsbDeviceAttached")
            return
        }
        if (deviceState is UsbDeviceState.PermissionGranted) {
            Log.i(TAG, "Device is already in PermissionGranted state. Ignoring onUsbDeviceAttached")
            return
        }
        val hasPermission = getUsbManager()?.hasPermission(usbDevice)
        Log.i(TAG, "${usbDevice.loggingInfo()} device attached hasPermission -> $hasPermission")
        if (hasPermission == true) {
            Log.i(TAG, "Device state change: $deviceState ->  PermissionGranted")
            setState(UsbDeviceState.PermissionGranted(usbDevice))
        } else {
            val foundDevice = findUvcDevice()
            if (foundDevice != null && getUsbManager()?.hasPermission(foundDevice) == true) {
                Log.i(TAG, "Found Device state: $deviceState ->  PermissionGranted")
                setState(UsbDeviceState.PermissionGranted(usbDevice))
            } else {
                Log.i(TAG, "Found device state: $deviceState ->  PermissionRequired")
                setState(UsbDeviceState.PermissionRequired(usbDevice))
            }
        }
    }

    fun onUsbPermissionGranted(usbDevice: UsbDevice) {
        controller.connect(usbDevice)
    }

    @Suppress("PrivatePropertyName")
    private val AV_DEVICE_USB_CLASSES: IntArray =
        intArrayOf(
            UsbConstants.USB_CLASS_VIDEO,
            UsbConstants.USB_CLASS_AUDIO,
        )

    private fun UsbDevice.loggingInfo(): String = "$productName by $manufacturerName at $deviceName"

    fun isUvcDevice(device: UsbDevice): Boolean {
        return device.deviceClass in AV_DEVICE_USB_CLASSES ||
                isMiscDeviceWithInterfaceInAnyDeviceClass(device, AV_DEVICE_USB_CLASSES)
    }

    private fun isMiscDeviceWithInterfaceInAnyDeviceClass(
        device: UsbDevice,
        deviceClasses: IntArray
    ): Boolean {
        return device.deviceClass == UsbConstants.USB_CLASS_MISC &&
                (0 until device.interfaceCount).any {
                    device.getInterface(it).interfaceClass in deviceClasses
                }
    }

    private val mutableStartStopFlow = MutableStateFlow(Unit)
    val startStopSignal: Flow<Unit> = mutableStartStopFlow.asStateFlow().onCompletion {
        stopStreaming()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 10_000), Unit)
}

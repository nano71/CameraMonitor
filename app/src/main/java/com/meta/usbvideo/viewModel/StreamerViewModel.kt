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
import android.app.PendingIntent
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import com.meta.usbvideo.R
import com.meta.usbvideo.UsbSpeed
import com.meta.usbvideo.UsbVideoNativeLibrary
import com.meta.usbvideo.connection.VideoFormat
import com.meta.usbvideo.eventloop.EventLooper
import com.meta.usbvideo.permission.CameraPermissionRequested
import com.meta.usbvideo.permission.CameraPermissionState
import com.meta.usbvideo.permission.PermissionStatus
import com.meta.usbvideo.permission.RecordAudioPermissionRequested
import com.meta.usbvideo.permission.RecordAudioPermissionState
import com.meta.usbvideo.permission.toCameraState
import com.meta.usbvideo.permission.toRecordAudioState
import com.meta.usbvideo.usb.UsbDeviceState
import com.meta.usbvideo.usb.UsbMonitor
import com.meta.usbvideo.usb.UsbMonitor.findUvcDevice
import com.meta.usbvideo.usb.UsbMonitor.getUsbManager
import com.meta.usbvideo.usb.UsbMonitor.setState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn

private const val TAG = "StreamerViewModel"
private const val ACTION_USB_PERMISSION: String = "com.meta.usbvideo.USB_PERMISSION"

/** Reactively monitors the state of USB AVC device and implements state transitions methods */
class StreamerViewModel(
    private val application: Application,
    cameraPermission: CameraPermissionState,
    recordAudioPermission: RecordAudioPermissionState,
) : AndroidViewModel(application) {

    var videoFormat: VideoFormat? = null
    var videoFormats: List<VideoFormat> = emptyList()
    private var usbPermissionRequestedAtMs: Long = 0L

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
        MutableStateFlow(cameraPermission)
    val cameraPermissionStateFlow: StateFlow<CameraPermissionState> =
        cameraPermissionInternalState.asStateFlow()

    private val recordAudioPermissionInternalState: MutableStateFlow<RecordAudioPermissionState> =
        MutableStateFlow(recordAudioPermission)
    val recordAudioPermissionStateFlow: StateFlow<RecordAudioPermissionState> =
        recordAudioPermissionInternalState.asStateFlow()

    private val videoSurfaceStateFlow = MutableStateFlow<Surface?>(null)

    fun surfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        videoSurfaceStateFlow.value = Surface(surfaceTexture)
    }

    fun surfaceTextureDestroyed(surfaceTexture: SurfaceTexture) {
        Log.e(TAG, "surfaceTextureDestroyed")
        videoSurfaceStateFlow.value?.release()
        videoSurfaceStateFlow.value = null
    }

    private suspend fun genSurface(): Surface {
        return videoSurfaceStateFlow.filterNotNull().first()
    }

    suspend fun onUsbDeviceConnected(usbDeviceState: UsbDeviceState.Connected) {
        Log.i(TAG, "usbDeviceState is UsbDeviceState.Connected")
        usbDeviceState.videoStreamingConnection.let {
            videoFormats = it.videoFormats
            videoFormat = it.findBestVideoFormat(1920, 1080)
        }
        val videoStreamingSurface = genSurface()
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
                    videoFormat,
                ).also {
                    UsbVideoNativeLibrary.startUsbVideoStreamingNative()
                }
            }
        Log.i(TAG, "startUsbVideoStreaming $videoStreamStatus, $videoStreamMessage")
        setState(
            UsbDeviceState.Streaming(
                usbDeviceState.usbDevice,
                usbDeviceState.audioStreamingConnection,
                audioStreamStatus,
                audioStreamMessage,
                usbDeviceState.videoStreamingConnection,
                videoStreamStatus,
                videoStreamMessage,
            )
        )
    }

    fun onUsbDeviceDetached() {
        Log.i(TAG, "usbDeviceState is UsbDeviceState.Detached")
        EventLooper.call {
            UsbVideoNativeLibrary.stopUsbAudioStreamingNative()
            UsbVideoNativeLibrary.stopUsbVideoStreamingNative()
            UsbVideoNativeLibrary.disconnectUsbAudioStreamingNative()
            UsbVideoNativeLibrary.disconnectUsbVideoStreamingNative()
            UsbMonitor.disconnect()
        }
    }

    fun onStreamingStopRequested(usbDeviceState: UsbDeviceState.StreamingStop) {
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

    fun onStreamingRestartRequested(usbDeviceState: UsbDeviceState.StreamingRestart) {
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

    fun getStreamingStatsSummaryString(): String {
        val usbDeviceState = UsbMonitor.usbDeviceState
        return if (usbDeviceState is UsbDeviceState.Streaming) {
            val productName = usbDeviceState.usbDevice.productName
            val usbSpeed: String? = getUsbSpeedLabel(UsbVideoNativeLibrary.getUsbSpeed())
            val line1 = arrayOf(productName, usbSpeed).filterNotNull().joinToString(" \u2022 ")
            val stats = UsbVideoNativeLibrary.streamingStatsSummaryString()
            arrayOf(line1, stats).toList().joinToString("\n")
        } else {
            ""
        }
    }

    fun getUsbSpeedLabel(usbSpeed: UsbSpeed): String? {
        return when (usbSpeed) {
            UsbSpeed.Unknown -> null
            UsbSpeed.Low -> application.getString(R.string.usb_speed_low)
            UsbSpeed.Full -> application.getString(R.string.usb_speed_full)
            UsbSpeed.High -> application.getString(R.string.usb_speed_high)
            UsbSpeed.Super -> application.getString(R.string.usb_speed_super)
            UsbSpeed.SuperPlus -> application.getString(R.string.usb_speed_super_plus)
        }
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

    fun markCameraPermissionRequested() {
        updateCameraPermissionState(CameraPermissionRequested)
    }

    fun markRecordAudioPermissionRequested() {
        updateRecordAudioPermissionState(RecordAudioPermissionRequested)
    }

    fun updateCameraPermissionFromStatus(permissionStatus: PermissionStatus) {
        updateCameraPermissionState(permissionStatus.toCameraState())
    }

    fun updateRecordAudioPermissionFromStatus(permissionStatus: PermissionStatus) {
        updateRecordAudioPermissionState(permissionStatus.toRecordAudioState())
    }

    suspend fun requestUsbPermission(lifecycle: Lifecycle) {
        delay(1000)
        val lifecycleState: Lifecycle.State = lifecycle.currentState
        val deviceState: UsbDeviceState = UsbMonitor.usbDeviceState
        Log.i(TAG, "lifecycle: $lifecycleState usbDeviceState: $deviceState")
        if (lifecycleState == Lifecycle.State.RESUMED &&
            (!(deviceState is UsbDeviceState.Streaming || deviceState is UsbDeviceState.Connected))
        ) {
            findUvcDevice()?.let { askUserUsbDevicePermission(it) }
        } else {
            Log.i(TAG, "Usb permission are likely requested. State: $deviceState")
        }
    }

    private fun updateCameraPermissionState(cameraPermission: CameraPermissionState) {
        Log.i(TAG, "updateCameraPermissionState to $cameraPermission")
        cameraPermissionInternalState.value = cameraPermission
    }

    private fun updateRecordAudioPermissionState(recordAudioPermission: RecordAudioPermissionState) {
        Log.i(TAG, "recordAudioPermission set to $recordAudioPermission")
        recordAudioPermissionInternalState.value = recordAudioPermission
    }

    private fun askUserUsbDevicePermission(device: UsbDevice) {
        val usbManager: UsbManager = getUsbManager() ?: return
        if (usbManager.hasPermission(device)) {
            when (UsbMonitor.usbDeviceState) {
                is UsbDeviceState.Connected -> Log.i(TAG, "askUserUsbDevicePermission: device already connected. Skipping")
                is UsbDeviceState.Streaming -> Log.i(TAG, "askUserUsbDevicePermission: device already streaming. Skipping")
                else -> {
                    usbPermissionRequestedAtMs = 0L
                    Log.i(TAG, "askUserUsbDevicePermission: device already have permission. Updating state.")
                    setState(UsbDeviceState.PermissionGranted(device))
                }
            }
        } else {
            Log.i(TAG, "Requesting USB permission")
            usbPermissionRequestedAtMs = SystemClock.uptimeMillis()
            setState(UsbDeviceState.PermissionRequested(device))
            val permissionIntent =
                PendingIntent.getBroadcast(application, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    suspend fun refreshUsbPermissionStateFromSystem() {
        val usbManager = getUsbManager() ?: return
        val state = UsbMonitor.usbDeviceState
        Log.i(TAG, "refreshUsbPermissionStateFromSystem() called with: state = $state")
        val device =
            when (state) {
                is UsbDeviceState.PermissionRequired -> state.usbDevice
                is UsbDeviceState.PermissionRequested -> state.usbDevice
                is UsbDeviceState.PermissionDenied -> state.usbDevice
                is UsbDeviceState.Attached -> state.usbDevice
                else -> null
            } ?: return

        if (usbManager.hasPermission(device)) {
            usbPermissionRequestedAtMs = 0L
            Log.i(TAG, "refreshUsbPermissionStateFromSystem: permission is granted, recovering state")
            setState(UsbDeviceState.PermissionGranted(device))
            return
        }

        if (state is UsbDeviceState.PermissionRequested || state is UsbDeviceState.PermissionRequired || state is UsbDeviceState.PermissionDenied) {
            delay(2000)
            Log.i(TAG, "refreshUsbPermissionStateFromSystem: permission not granted, triggering fallback request")
            askUserUsbDevicePermission(device)
        }
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
        UsbMonitor.connect(usbDevice)
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

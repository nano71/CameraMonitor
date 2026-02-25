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
package com.meta.usbvideo

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import com.meta.usbvideo.eventloop.EventLooper
import com.meta.usbvideo.permission.CameraPermissionRequested
import com.meta.usbvideo.permission.CameraPermissionRequired
import com.meta.usbvideo.permission.CameraPermissionState
import com.meta.usbvideo.permission.RecordAudioPermissionRequested
import com.meta.usbvideo.permission.RecordAudioPermissionRequired
import com.meta.usbvideo.permission.RecordAudioPermissionState
import com.meta.usbvideo.permission.getPermissionStatus
import com.meta.usbvideo.permission.toCameraState
import com.meta.usbvideo.permission.toRecordAudioState
import com.meta.usbvideo.usb.UsbDeviceState
import com.meta.usbvideo.usb.UsbMonitor
import com.meta.usbvideo.usb.UsbMonitor.findUvcDevice
import com.meta.usbvideo.usb.UsbMonitor.getUsbManager
import com.meta.usbvideo.usb.UsbMonitor.setState
import com.meta.usbvideo.usb.VideoFormat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface UiAction

object Initialize : UiAction

object RequestCameraPermission : UiAction

object RequestRecordAudioPermission : UiAction

object RequestUsbPermission : UiAction

object PresentStreamingScreen : UiAction
object DismissStreamingScreen : UiAction

private const val TAG = "StreamerViewModel"
private const val ACTION_USB_PERMISSION: String = "com.meta.usbvideo.USB_PERMISSION"

/** Reactively monitors the state of USB AVC device and implements state transitions methods */
class StreamerViewModel(
    private val application: Application,
    cameraPermission: CameraPermissionState,
    recordAudioPermission: RecordAudioPermissionState,
) : AndroidViewModel(application) {

    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var recordAudioPermissionLauncher: ActivityResultLauncher<String>

    var videoFormat: VideoFormat? = null
    var videoFormats: List<VideoFormat> = emptyList()
    private var usbPermissionRequestedAtMs: Long = 0L

//    fun setVideoFormatAt(index: Int) {
//        videoFormat = videoFormats[index]
//    }

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

    fun prepareCameraPermissionLaunchers(activity: ComponentActivity) {
        cameraPermissionLauncher =
            activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                updateCameraPermissionState(
                    activity.getPermissionStatus(Manifest.permission.CAMERA).toCameraState()
                )
            }
        recordAudioPermissionLauncher =
            activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                updateRecordAudioPermissionState(
                    activity.getPermissionStatus(Manifest.permission.RECORD_AUDIO).toRecordAudioState()
                )
            }
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    recordAudioPermissionLauncher.unregister()
                    cameraPermissionLauncher.unregister()
                }
            })
    }

    fun prepareUsbBroadcastReceivers(activity: ComponentActivity) {
        activity.registerReceiver(usbReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED))
        activity.registerReceiver(usbReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        }
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                Log.i(TAG, "LifecycleEventObserver called with: event = $event")
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        MainScope().launch { refreshUsbPermissionStateFromSystem() }
                    }

                    Lifecycle.Event.ON_DESTROY -> activity.unregisterReceiver(usbReceiver)
                    else -> Unit
                }
            })
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

    fun uiActionFlow(): Flow<UiAction> {
        return combineTransform(
            cameraPermissionStateFlow,
            recordAudioPermissionStateFlow,
            UsbMonitor.usbDeviceStateFlow,
        ) { cameraPermissionState: CameraPermissionState,
            recordAudioPermissionState: RecordAudioPermissionState,
            usbDeviceState: UsbDeviceState ->
//            Log.i(TAG, "$cameraPermissionState, $recordAudioPermissionState $usbDeviceState")
            when {
                cameraPermissionState is CameraPermissionRequired -> {
                    emit(RequestCameraPermission)
                }

                cameraPermissionState is CameraPermissionRequested -> {
                    Log.i(TAG, "CameraPermissionRequested. No op")
                }

                recordAudioPermissionState is RecordAudioPermissionRequired -> {
                    emit(RequestRecordAudioPermission)
                }

                recordAudioPermissionState is RecordAudioPermissionRequested -> {
                    Log.i(TAG, "RecordAudioPermissionRequested. No op")
                }

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
                    onUsbPermissionGranted(usbDeviceState.usbDevice)
                }

                usbDeviceState is UsbDeviceState.Attached -> {
                    onUsbDeviceAttached(usbDeviceState.usbDevice)
                }

                usbDeviceState is UsbDeviceState.Detached -> {
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
                        videoFormats = it.videoFormats
                        videoFormat = it.findBestVideoFormat(1920, 1080)
                    }
                    emit(PresentStreamingScreen)
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
                        //UsbVideoNativeLibrary.stopUsbVideoStreamingNative()
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


    fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        updateCameraPermissionState(CameraPermissionRequested)
    }

    fun requestRecordAudioPermission() {
        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        updateRecordAudioPermissionState(RecordAudioPermissionRequested)
    }

    suspend fun requestUsbPermission(lifecycle: Lifecycle) {
        // In some instances, Android presents the USB device permission dialog and
        // sends onNewIntent to the activity. So, before requesting USB permission, we
        // yield here to see if the current activity is still in resume state after a
        // short delay
        delay(1000)
        val lifecycleState: Lifecycle.State = lifecycle.currentState
        val deviceState: UsbDeviceState = UsbMonitor.usbDeviceState
        Log.i(TAG, "lifecycle: $lifecycleState usbDeviceState: $deviceState")
        if (lifecycleState == Lifecycle.State.RESUMED &&
            (!(deviceState is UsbDeviceState.Streaming ||
                    deviceState is UsbDeviceState.Connected))
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

    @Suppress("PrivatePropertyName")
    private val AV_DEVICE_USB_CLASSES: IntArray =
        intArrayOf(
            UsbConstants.USB_CLASS_VIDEO,
            UsbConstants.USB_CLASS_AUDIO,
        )

    private fun UsbDevice.loggingInfo(): String = "$productName by $manufacturerName at $deviceName"
    private val usbReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action
                val device =
                    when (action) {
                        UsbManager.ACTION_USB_DEVICE_ATTACHED,
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                            IntentCompat.getParcelableExtra(
                                intent,
                                UsbManager.EXTRA_DEVICE,
                                UsbDevice::class.java,
                            )
                        }

                        ACTION_USB_PERMISSION -> findUvcDevice()
                        else -> null
                    }

                val isUvc = device != null && isUvcDevice(device)
                Log.i(
                    TAG,
                    "Received Broadcast $action for ${if (isUvc) "UVC" else "non-UVC"} device ${device?.loggingInfo()}"
                )

                if (device == null || !isUvc) {
                    return
                }
                when (action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        // resolve device using usb manager because in hasPermission may return false
                        // negative value on attach
                        setState(UsbDeviceState.Attached(findUvcDevice() ?: device))
                    }

                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        setState(UsbDeviceState.Detached(device))
                    }

                    ACTION_USB_PERMISSION -> {
                        usbPermissionRequestedAtMs = 0L
                        if (getUsbManager()?.hasPermission(device) == true) {
                            Log.i(TAG, "Permission granted for device ${device.loggingInfo()}")
                            setState(UsbDeviceState.PermissionGranted(device))
                        } else {
                            Log.i(TAG, "Permission denied for device ${device.loggingInfo()}")
                            setState(UsbDeviceState.PermissionDenied(device))
                        }
                    }
                }
            }
        }

    private fun askUserUsbDevicePermission(device: UsbDevice) {
        val usbManager: UsbManager = getUsbManager() ?: return
        if (usbManager.hasPermission(device)) {
            when (UsbMonitor.usbDeviceState) {
                is UsbDeviceState.Connected -> {
                    Log.i(TAG, "askUserUsbDevicePermission: device already connected. Skipping")
                }

                is UsbDeviceState.Streaming -> {
                    Log.i(TAG, "askUserUsbDevicePermission: device already streaming. Skipping")
                }

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
            // Request permission from user
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private suspend fun refreshUsbPermissionStateFromSystem() {
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

    private fun isUvcDevice(device: UsbDevice): Boolean {
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

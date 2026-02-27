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

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.nano71.cameramonitor.R
import com.nano71.cameramonitor.core.permission.CameraPermissionRequested
import com.nano71.cameramonitor.core.permission.CameraPermissionRequired
import com.nano71.cameramonitor.core.permission.RecordAudioPermissionRequested
import com.nano71.cameramonitor.core.permission.RecordAudioPermissionRequired
import com.nano71.cameramonitor.core.permission.getCameraPermissionState
import com.nano71.cameramonitor.core.permission.getPermissionStatus
import com.nano71.cameramonitor.core.permission.getRecordAudioPermissionState
import com.nano71.cameramonitor.core.usb.UsbDeviceState
import com.nano71.cameramonitor.core.usb.UsbMonitor
import com.nano71.cameramonitor.core.usb.UsbMonitor.findUvcDevice
import com.nano71.cameramonitor.core.usb.UsbMonitor.getUsbManager
import com.nano71.cameramonitor.core.usb.UsbMonitor.setState
import com.nano71.cameramonitor.feature.streamer.adapter.StreamerScreensAdapter
import com.nano71.cameramonitor.feature.streamer.animation.ZoomOutPageTransformer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.launch

private const val TAG = "StreamerActivity"

sealed interface UiAction

object RequestCameraPermission : UiAction
object RequestRecordAudioPermission : UiAction
object RequestUsbPermission : UiAction
object PresentStreamingScreen : UiAction
object DismissStreamingScreen : UiAction

private const val ACTION_USB_PERMISSION: String = "com.nano71.usbvideo.USB_PERMISSION"

enum class StreamerScreen {
    Status,
    Streaming,
}

class StreamerActivity : ComponentActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var recordAudioPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var screensAdapter: StreamerScreensAdapter

    private val streamerViewModel: StreamerViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "StreamerActivity.onResume() called")
        lifecycleScope.launch { refreshUsbPermissionStateFromSystem() }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "StreamerActivity.onDestroy() called")
        Process.killProcess(Process.myPid())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) {
            moveTaskToBack(true)
        }
        doOnCreate()
    }

    private fun doOnCreate() {
        prepareCameraPermissionLaunchers()
        prepareUsbBroadcastReceivers()
        setContentView(R.layout.activity_streamer)
        viewPager = findViewById(R.id.view_pager)
        viewPager.offscreenPageLimit = 1
        viewPager.setPageTransformer(ZoomOutPageTransformer())
        screensAdapter = StreamerScreensAdapter(this, streamerViewModel)
        streamerViewModel.updateRecordAudioPermissionState(getRecordAudioPermissionState())
        streamerViewModel.updateCameraPermissionState(getCameraPermissionState())
        viewPager.adapter = screensAdapter
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                streamerViewModel.restartStreaming()
                streamerViewModel.startStopSignal.collect {}
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                uiActionFlow().collect {
                    when (it) {
                        RequestCameraPermission -> {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            streamerViewModel.updateCameraPermissionState(CameraPermissionRequested)
                        }

                        RequestRecordAudioPermission -> {
                            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            streamerViewModel.updateRecordAudioPermissionState(RecordAudioPermissionRequested)
                        }

                        RequestUsbPermission -> {
                            Log.i(TAG, "RequestUsbPermission called")
                            if (lifecycle.currentState == Lifecycle.State.RESUMED) {
                                requestUsbPermission()
                            }
                        }

                        PresentStreamingScreen -> {
                            startStreaming()
                        }

                        DismissStreamingScreen -> {
                            stopStreaming()
                        }

                    }
                }
            }
        }
    }

    private fun uiActionFlow(): Flow<UiAction> {
        return combineTransform(
            streamerViewModel.cameraPermissionStateFlow,
            streamerViewModel.recordAudioPermissionStateFlow,
            UsbMonitor.usbDeviceStateFlow,
        ) { cameraPermissionState, recordAudioPermissionState, usbDeviceState ->
            Log.i(
                TAG,
                "uiActionFlow() called with: usbDeviceState = $usbDeviceState"
            )
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
                    emit(RequestUsbPermission)
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
                    streamerViewModel.onUsbDeviceDetached(usbDeviceState)
                    emit(DismissStreamingScreen)
                }

                usbDeviceState is UsbDeviceState.Connected -> {
                    emit(PresentStreamingScreen)
                    streamerViewModel.onUsbDeviceConnected(application.applicationContext, usbDeviceState)
                }

                usbDeviceState is UsbDeviceState.StreamingStop -> {
                    streamerViewModel.onStreamingStopRequested(usbDeviceState)
                }

                usbDeviceState is UsbDeviceState.StreamingRestart -> {
                    streamerViewModel.onStreamingRestartRequested(usbDeviceState)
                }
            }
        }
    }

    private fun startStreaming() {
        Log.i(TAG, "startStreaming() called")
        if (!screensAdapter.screens.contains(StreamerScreen.Streaming)) {
            screensAdapter.screens = listOf(StreamerScreen.Status, StreamerScreen.Streaming)
            screensAdapter.notifyItemInserted(1)
            viewPager.setCurrentItem(1, true)
        }
    }

    private fun stopStreaming() {
        Log.i(TAG, "stopStreaming() called")
        val screensCount = screensAdapter.screens.size

        if (screensCount > 1) {
            if (viewPager.currentItem == 0) {
                screensAdapter.screens = listOf(StreamerScreen.Status)
                screensAdapter.notifyItemRangeRemoved(1, screensCount - 1)
                return
            }

            viewPager.setCurrentItem(0, true)

            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrollStateChanged(state: Int) {
                    if (state == ViewPager2.SCROLL_STATE_IDLE &&
                        viewPager.currentItem == 0
                    ) {
                        viewPager.unregisterOnPageChangeCallback(this)
                        viewPager.post {
                            screensAdapter.screens = listOf(StreamerScreen.Status)
                            screensAdapter.notifyItemRangeRemoved(1, screensCount - 1)
                        }
                    }
                }
            })
        }
    }

    private fun prepareCameraPermissionLaunchers() {
        cameraPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                streamerViewModel.updateCameraPermissionFromStatus(
                    getPermissionStatus(Manifest.permission.CAMERA)
                )
            }
        recordAudioPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                streamerViewModel.updateRecordAudioPermissionFromStatus(
                    getPermissionStatus(Manifest.permission.RECORD_AUDIO)
                )
            }
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    recordAudioPermissionLauncher.unregister()
                    cameraPermissionLauncher.unregister()
                }
            })
    }

    private fun prepareUsbBroadcastReceivers() {
        registerReceiver(usbReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED))
        registerReceiver(usbReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION), RECEIVER_NOT_EXPORTED)
        }
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    unregisterReceiver(usbReceiver)
                }
            })
    }

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

                val isUvc = device != null && streamerViewModel.isUvcDevice(device)
                Log.i(
                    TAG,
                    "Received Broadcast $action for ${if (isUvc) "UVC" else "non-UVC"} device ${device?.loggingInfo()}"
                )

                if (device == null || !isUvc) {
                    return
                }
                when (action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        setState(UsbDeviceState.Attached(findUvcDevice() ?: device))
                    }

                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        setState(UsbDeviceState.Detached(device))
                    }

                    ACTION_USB_PERMISSION -> {
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
                is UsbDeviceState.Connected -> Log.i(TAG, "askUserUsbDevicePermission: device already connected. Skipping")
                is UsbDeviceState.Streaming -> Log.i(TAG, "askUserUsbDevicePermission: device already streaming. Skipping")
                else -> {
                    Log.i(TAG, "askUserUsbDevicePermission: device already have permission. Updating state.")
                    setState(UsbDeviceState.PermissionGranted(device))
                }
            }
        } else {
            Log.i(TAG, "Requesting USB permission")
            setState(UsbDeviceState.PermissionRequested(device))
            val permissionIntent =
                PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
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

    private fun requestUsbPermission() {
        val lifecycleState: Lifecycle.State = this.lifecycle.currentState
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
}

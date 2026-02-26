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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
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
import com.meta.usbvideo.animation.ZoomOutPageTransformer
import com.meta.usbvideo.permission.CameraPermissionRequested
import com.meta.usbvideo.permission.CameraPermissionRequired
import com.meta.usbvideo.permission.RecordAudioPermissionRequested
import com.meta.usbvideo.permission.RecordAudioPermissionRequired
import com.meta.usbvideo.permission.getCameraPermissionState
import com.meta.usbvideo.permission.getPermissionStatus
import com.meta.usbvideo.permission.getRecordAudioPermissionState
import com.meta.usbvideo.usb.UsbDeviceState
import com.meta.usbvideo.usb.UsbMonitor
import com.meta.usbvideo.usb.UsbMonitor.findUvcDevice
import com.meta.usbvideo.usb.UsbMonitor.getUsbManager
import com.meta.usbvideo.usb.UsbMonitor.setState
import com.meta.usbvideo.viewModel.StreamerViewModel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.launch

private const val TAG = "StreamerActivity"

sealed interface UiAction
object Initialize : UiAction
object RequestCameraPermission : UiAction
object RequestRecordAudioPermission : UiAction
object RequestUsbPermission : UiAction
object PresentStreamingScreen : UiAction
object DismissStreamingScreen : UiAction

private const val ACTION_USB_PERMISSION: String = "com.meta.usbvideo.USB_PERMISSION"

enum class StreamerScreen {
    Status,
    Streaming,
}

class StreamerActivity : ComponentActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var recordAudioPermissionLauncher: ActivityResultLauncher<String>

    private val streamerViewModel: StreamerViewModel by viewModels {
        StreamerViewModelFactory(getCameraPermissionState(), getRecordAudioPermissionState())
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "StreamerActivity.onResume() called")
        MainScope().launch { streamerViewModel.refreshUsbPermissionStateFromSystem() }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "StreamerActivity.onDestroy() called")
        android.os.Process.killProcess(android.os.Process.myPid())
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
        val screensAdapter = StreamerScreensAdapter(this, streamerViewModel)
        viewPager.adapter = screensAdapter
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                streamerViewModel.restartStreaming()
                streamerViewModel.startStopSignal.collect {}
            }
        }
        lifecycleScope.launch {
            uiActionFlow().collect {
                when (it) {
                    Initialize -> {
                        viewPager.setCurrentItem(0, true)
                    }

                    RequestCameraPermission -> {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        streamerViewModel.markCameraPermissionRequested()
                    }

                    RequestRecordAudioPermission -> {
                        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        streamerViewModel.markRecordAudioPermissionRequested()
                    }

                    RequestUsbPermission -> {
                        Log.i(TAG, "RequestUsbPermission called")
                        if (lifecycle.currentState == Lifecycle.State.RESUMED) {
                            streamerViewModel.requestUsbPermission(this@StreamerActivity.lifecycle)
                        }
                    }

                    PresentStreamingScreen -> {
                        if (!screensAdapter.screens.contains(StreamerScreen.Streaming)) {
                            screensAdapter.screens = listOf(StreamerScreen.Status, StreamerScreen.Streaming)
                            screensAdapter.notifyItemInserted(1)
                            viewPager.setCurrentItem(1, true)
                        }
                    }

                    DismissStreamingScreen -> {
                        stopStreaming(screensAdapter)
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
                    streamerViewModel.onUsbDeviceDetached()
                    emit(DismissStreamingScreen)
                }

                usbDeviceState is UsbDeviceState.Connected -> {
                    emit(PresentStreamingScreen)
                    streamerViewModel.onUsbDeviceConnected(usbDeviceState)
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
            registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        }
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    unregisterReceiver(usbReceiver)
                }
            })
    }

    private fun stopStreaming(screensAdapter: StreamerScreensAdapter) {
        Log.i(TAG, "stopStreaming() called")
        val screensCount = screensAdapter.screens.size
        if (screensCount > 1) {

            viewPager.setCurrentItem(0, true)

            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrollStateChanged(state: Int) {
                    if (state == ViewPager2.SCROLL_STATE_IDLE &&
                        viewPager.currentItem == 0
                    ) {
                        viewPager.unregisterOnPageChangeCallback(this)

                        screensAdapter.screens = listOf(StreamerScreen.Status)
                        screensAdapter.notifyItemRangeRemoved(1, screensCount - 1)
                    }
                }
            })
        }
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
}

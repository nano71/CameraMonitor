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

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.meta.usbvideo.animation.ZoomOutPageTransformer
import com.meta.usbvideo.permission.PermissionsViewModel
import com.meta.usbvideo.permission.RequestCameraPermission
import com.meta.usbvideo.permission.RequestRecordAudioPermission
import com.meta.usbvideo.viewModel.DismissStreamingScreen
import com.meta.usbvideo.viewModel.Initialize
import com.meta.usbvideo.viewModel.PresentStreamingScreen
import com.meta.usbvideo.viewModel.RequestUsbPermission
import com.meta.usbvideo.viewModel.StreamerUiActionDelegate
import com.meta.usbvideo.viewModel.StreamerViewModel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val TAG = "StreamerActivity"

enum class StreamerScreen {
    Status,
    Streaming,
}

class StreamerActivity : ComponentActivity() {

    private lateinit var viewPager: ViewPager2

    private val streamerViewModel: StreamerViewModel by viewModels()
    private val permissionsViewModel: PermissionsViewModel by viewModels()
    private val streamerUiActionDelegate by lazy { StreamerUiActionDelegate(application) }

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
        permissionsViewModel.preparePermissionLaunchers(this)
        streamerViewModel.prepareUsbBroadcastReceivers(this)
        setContentView(R.layout.activity_streamer)
        viewPager = findViewById(R.id.view_pager)
        viewPager.offscreenPageLimit = 1
        viewPager.setPageTransformer(ZoomOutPageTransformer())
        val screensAdapter = StreamerScreensAdapter(this, streamerViewModel, permissionsViewModel)
        viewPager.adapter = screensAdapter
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                streamerViewModel.restartStreaming()

                launch {
                    streamerViewModel.startStopSignal.collect()
                }

                launch {
                    permissionsViewModel.uiActionFlow().collect {
                        when (it) {
                            RequestCameraPermission -> permissionsViewModel.requestCameraPermission()
                            RequestRecordAudioPermission -> permissionsViewModel.requestRecordAudioPermission()
                        }
                    }
                }

                launch {
                    streamerUiActionDelegate.uiActionFlow(streamerViewModel).collect {
                        when (it) {
                            Initialize -> {
                                viewPager.setCurrentItem(0, true)
                            }

                            RequestUsbPermission -> {
                                // no-op here but handle separately below by checking for status of USB permission in
                                // onResume because we yield to OS to avoid double permission dialog.
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
        }
    }

    private fun stopStreaming(screensAdapter: StreamerScreensAdapter) {
        Log.i(TAG, "stopStreaming() called")
        val screensCount = screensAdapter.screens.size
        if (screensCount > 1) {
            screensAdapter.screens = listOf(StreamerScreen.Status)
            screensAdapter.notifyItemRangeRemoved(1, screensCount - 1)
            viewPager.setCurrentItem(0, true)
        }
    }
}

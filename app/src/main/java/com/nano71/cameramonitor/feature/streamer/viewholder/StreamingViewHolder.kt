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
package com.nano71.cameramonitor.feature.streamer.viewholder

import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.nano71.cameramonitor.R
import com.nano71.cameramonitor.feature.streamer.StreamerScreen
import com.nano71.cameramonitor.feature.streamer.StreamerViewModel
import com.nano71.cameramonitor.feature.streamer.ui.VideoContainerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "StreamingViewHolder"

class StreamingViewHolder(
    private val rootView: View,
    private val streamerViewModel: StreamerViewModel,
    private val onNavigate: (StreamerScreen) -> Unit
) : RecyclerView.ViewHolder(rootView) {

    val streamStatsTextView: TextView = rootView.findViewById(R.id.stream_stats_text)
    val videoContainerView: VideoContainerView = rootView.findViewById(R.id.video_container)
    val bottomToolbar: LinearLayout = rootView.findViewById(R.id.bottom_toolbar)
    val backButton: View = bottomToolbar.findViewById(R.id.back_button)
    val gridButton: View = bottomToolbar.findViewById(R.id.grid_button)
    val zebraPrintButton: View = bottomToolbar.findViewById(R.id.texture_button)

    var operating = false
    var showZebra = false

    init {
        val videoFormat = streamerViewModel.videoFormat
        val width = videoFormat?.width ?: 1920
        val height = videoFormat?.height ?: 1080

        videoContainerView.initialize(width, height)

        setupToolbarToggle()
        setupToolbarButtons()
    }

    // 添加此方法用于观察和定时刷新
    fun observeViewModel(lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.lifecycleScope.launch {
            // 仅在生命周期处于 STARTED 时运行，离开页面会自动暂停，销毁时自动取消
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    streamStatsTextView.text = streamerViewModel.getVideoStreamInfoString()
                    delay(1000L) // 每秒刷新一次
                }
            }
        }
    }
    private fun setupToolbarButtons() {
        backButton.setOnClickListener {
            Log.i(TAG, "offButton clicked")
            onNavigate(StreamerScreen.Status)
        }
        gridButton.setOnClickListener {
            videoContainerView.toggleGridVisible()
        }
        zebraPrintButton.setOnClickListener {
            showZebra = !showZebra
            videoContainerView.setZebraVisible(showZebra)
        }
    }

    private fun setupToolbarToggle() {
        rootView.postDelayed({
            if (!operating) {
                toggleToolbar()
            }
        }, 3000L)
        rootView.setOnClickListener {
            toggleToolbar()
        }
    }

    private fun toggleToolbar() {
        if (bottomToolbar.isVisible) {
            fadeOut(bottomToolbar)
        } else {
            fadeIn(bottomToolbar)
        }
    }

    private fun fadeIn(view: View) {
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .setDuration(200)
            .start()
    }

    private fun fadeOut(view: View) {
        view.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                view.visibility = View.GONE
            }
            .start()
    }
}

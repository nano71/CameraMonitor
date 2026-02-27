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

import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.nano71.cameramonitor.R
import com.nano71.cameramonitor.feature.streamer.StreamerViewModel
import com.nano71.cameramonitor.feature.streamer.ui.VideoContainerView

private const val TAG = "StreamingViewHolder"

class StreamingViewHolder(
    private val rootView: View,
    private val streamerViewModel: StreamerViewModel,
) : RecyclerView.ViewHolder(rootView) {

    val streamingStats: TextView = rootView.findViewById(R.id.streaming_stats)
    val videoFrame: VideoContainerView = rootView.findViewById(R.id.video_container)
    private var lastUpdatedAt = 0L

    init {
        val videoTextureView = TextureView(videoFrame.context)
        videoTextureView.surfaceTextureListener = setupSurfaceTextureListener()
        val videoFormat = streamerViewModel.videoFormat
        val width = videoFormat?.width ?: 1920
        val height = videoFormat?.height ?: 1080
        videoFrame.addVideoTextureView(videoTextureView, width, height)
        videoFrame.updateVideoInfo(streamerViewModel.getVideoStreamInfoString())
        showToggleTip()
    }

    private fun showToggleTip() {
        streamingStats.text = rootView.context.getText(R.string.streaming_stats_toggle_tip)
        streamingStats.isVisible = true
        rootView.postDelayed({
            streamingStats.isVisible = false
        }, 5000L)
    }


    fun setupSurfaceTextureListener(): TextureView.SurfaceTextureListener {
        return object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                Log.i(
                    TAG,
                    "onSurfaceTextureAvailable() called with: surface = $surfaceTexture, width = $width, height = $height"
                )
                streamerViewModel.surfaceTextureAvailable(surfaceTexture, width, height)
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                videoFrame.invalidate()
                Log.i(
                    TAG,
                    "onSurfaceTextureSizeChanged() called with: surface = $surface, width = $width, height = $height"
                )
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                Log.i(TAG, "onSurfaceTextureDestroyed() called")
                videoFrame.updateVideoInfo("")
                streamerViewModel.surfaceTextureDestroyed()
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                val now = SystemClock.uptimeMillis()
                if (now - lastUpdatedAt > 999) {
                    videoFrame.updateVideoInfo(streamerViewModel.getVideoStreamInfoString())
                    lastUpdatedAt = now
                }
            }
        }
    }
}

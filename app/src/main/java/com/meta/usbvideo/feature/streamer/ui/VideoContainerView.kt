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
package com.meta.usbvideo.feature.streamer.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Container view for video TextureView or SurfaceView. Implemented as a FrameLayout which can
 * enforce aspect ratio if passed from above.
 */
class VideoContainerView(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {

    private var videoView: TextureView? = null
    private val videoInfoView: TextView = TextView(context).apply {
        setTextColor(Color.WHITE)
//        setBackgroundColor(Color.argb(160, 0, 0, 0))
        textSize = 12f
        val padding = (8 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        gravity = Gravity.START
        visibility = GONE
    }

    init {
        setBackgroundColor(Color.BLACK)
        val margin = (12 * resources.displayMetrics.density).toInt()
        addView(
            videoInfoView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(margin, margin, margin, margin)
            }
        )
    }

    fun addVideoTextureView(videoView: TextureView, width: Int, height: Int) {
        this.videoView = videoView

        addView(videoView, 0, LayoutParams(width, height, Gravity.CENTER))
    }

    fun updateVideoInfo(videoInfoText: String) {
        videoInfoView.text = videoInfoText
        videoInfoView.visibility = if (videoInfoText.isNotBlank()) VISIBLE else GONE
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val videoView = this.videoView ?: return

        val width = videoView.width
        val height = videoView.height
        if (width > 0 && height > 0) {
            videoView.pivotX = width.toFloat() / 2f
            videoView.pivotY = height.toFloat() / 2f
            val scaleX = abs(right - left).toFloat() / width
            val scaleY = abs(bottom - top).toFloat() / height

            // Use a uniform scale so the view follows the source aspect ratio reported
            // by the UVC stream dimensions and avoids geometric stretching.
            val uniformScale = minOf(scaleX, scaleY)
            if (abs(uniformScale - 1.0f) > 0.0001f) {
                videoView.scaleX = uniformScale
                videoView.scaleY = uniformScale
            } else {
                videoView.scaleX = 1.0f
                videoView.scaleY = 1.0f
            }
        }
    }
}

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
package com.nano71.cameramonitor.feature.streamer.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.isVisible
import kotlin.math.abs

class VideoContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface SurfaceCallback {
        fun onAvailable(surface: SurfaceTexture, width: Int, height: Int)
        fun onDestroyed()
        fun onFrameUpdated()
    }

    private var textureView: TextureView? = null
    private var surfaceCallback: SurfaceCallback? = null
    private val gridOverlay = CameraGridOverlay(context)

    fun toggleGridVisible() {
        gridOverlay.visibility = if (gridOverlay.isVisible) GONE else VISIBLE
    }

    fun initialize(videoWidth: Int, videoHeight: Int) {
        if (this.textureView != null) return

        val textureView = TextureView(context)
        textureView.surfaceTextureListener = internalListener

        addView(textureView, 0, LayoutParams(videoWidth, videoHeight, Gravity.CENTER))
        addView(gridOverlay, 1, LayoutParams(videoWidth, videoHeight, Gravity.CENTER))
        this.textureView = textureView
    }

    fun setSurfaceCallback(callback: SurfaceCallback) {
        surfaceCallback = callback
    }

    private val internalListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(
            surface: SurfaceTexture,
            width: Int,
            height: Int
        ) {
            surfaceCallback?.onAvailable(surface, width, height)
        }

        override fun onSurfaceTextureSizeChanged(
            surface: SurfaceTexture,
            width: Int,
            height: Int
        ) {
            invalidate()
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            surfaceCallback?.onDestroyed()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            surfaceCallback?.onFrameUpdated()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val textureView = this.textureView ?: return

        val width = textureView.width
        val height = textureView.height
        if (width > 0 && height > 0) {
            textureView.pivotX = width.toFloat() / 2f
            textureView.pivotY = height.toFloat() / 2f
            val scaleX = abs(right - left).toFloat() / width
            val scaleY = abs(bottom - top).toFloat() / height

            // Use a uniform scale so the view follows the source aspect ratio reported
            // by the UVC stream dimensions and avoids geometric stretching.
            val uniformScale = minOf(scaleX, scaleY)
            if (abs(uniformScale - 1.0f) > 0.0001f) {
                textureView.scaleX = uniformScale
                textureView.scaleY = uniformScale
            } else {
                textureView.scaleX = 1.0f
                textureView.scaleY = 1.0f
            }
        }
    }
}

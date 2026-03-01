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
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.nano71.cameramonitor.core.usb.UsbVideoNativeLibrary
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

class VideoContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val renderer = UsbVideoNativeLibrary.VideoRenderer()

    interface SurfaceCallback {
        fun onSurfaceCreated()
        fun onSurfaceDestroyed()
        fun onFrameUpdated()
    }

    private var glSurfaceView: GLSurfaceView? = null
    private var surfaceCallback: SurfaceCallback? = null
    private val gridOverlay = CameraGridOverlay(context)

    fun toggleGridVisible() {
        gridOverlay.visibility = if (gridOverlay.isVisible) GONE else VISIBLE
    }

    fun setZebraVisible(visible: Boolean) {
        renderer.showZebra = visible
    }

    fun initialize(videoWidth: Int, videoHeight: Int) {
        if (this.glSurfaceView != null) return
        val glView = GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        addView(glView, 0, LayoutParams(videoWidth, videoHeight, Gravity.CENTER))

        addView(gridOverlay, 1, LayoutParams(videoWidth, videoHeight, Gravity.CENTER))
    }

    fun setSurfaceCallback(callback: SurfaceCallback) {
        surfaceCallback = callback
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val glSurfaceView = this.glSurfaceView ?: return

        val width = glSurfaceView.width
        val height = glSurfaceView.height
        if (width > 0 && height > 0) {
            glSurfaceView.pivotX = width.toFloat() / 2f
            glSurfaceView.pivotY = height.toFloat() / 2f
            val scaleX = abs(right - left).toFloat() / width
            val scaleY = abs(bottom - top).toFloat() / height

            val uniformScale = minOf(scaleX, scaleY)
            if (abs(uniformScale - 1.0f) > 0.0001f) {
                glSurfaceView.scaleX = uniformScale
                glSurfaceView.scaleY = uniformScale
            } else {
                glSurfaceView.scaleX = 1.0f
                glSurfaceView.scaleY = 1.0f
            }
        }
    }
}

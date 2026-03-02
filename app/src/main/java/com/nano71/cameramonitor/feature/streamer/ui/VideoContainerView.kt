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

class VideoContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val renderer = UsbVideoNativeLibrary.VideoRenderer()

    private var glSurfaceView: GLSurfaceView? = null
    private val gridOverlay = CameraGridOverlay(context)

    fun toggleGridVisible() {
        gridOverlay.visibility = if (gridOverlay.isVisible) GONE else VISIBLE
    }

    fun setZebraVisible(visible: Boolean) {
        renderer.showZebra = visible
    }

    fun initialize(videoWidth: Int, videoHeight: Int) {
        if (glSurfaceView != null) return
        glSurfaceView = GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        val params = LayoutParams(videoWidth, videoHeight, Gravity.CENTER)

        addView(glSurfaceView, params)
        addView(gridOverlay, params)
    }
}

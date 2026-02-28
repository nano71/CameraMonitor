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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class CameraGridOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
        alpha = 120  // 半透明
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        val oneThirdWidth = width / 3
        val oneThirdHeight = height / 3

        // 画两条竖线
        canvas.drawLine(oneThirdWidth, 0f, oneThirdWidth, height, paint)
        canvas.drawLine(oneThirdWidth * 2, 0f, oneThirdWidth * 2, height, paint)

        // 画两条横线
        canvas.drawLine(0f, oneThirdHeight, width, oneThirdHeight, paint)
        canvas.drawLine(0f, oneThirdHeight * 2, width, oneThirdHeight * 2, paint)
    }
}

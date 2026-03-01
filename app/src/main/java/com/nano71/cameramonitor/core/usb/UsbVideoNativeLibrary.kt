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
package com.nano71.cameramonitor.core.usb

import android.content.Context
import android.media.AudioManager
import android.media.AudioTrack
import android.view.Surface
import com.nano71.cameramonitor.core.connection.AudioStreamingConnection
import com.nano71.cameramonitor.core.connection.AudioStreamingFormatTypeDescriptor
import com.nano71.cameramonitor.core.connection.VideoFormat
import com.nano71.cameramonitor.core.connection.VideoStreamingConnection
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class UsbSpeed {
    Unknown,
    Low,
    Full,
    High,
    Super,
    SuperPlus,
}

object UsbVideoNativeLibrary {

    fun getUsbSpeed(): UsbSpeed = UsbSpeed.entries[getUsbDeviceSpeed()]

    private external fun getUsbDeviceSpeed(): Int

    fun connectUsbAudioStreaming(
        context: Context,
        audioStreamingConnection: AudioStreamingConnection,
    ): Pair<Boolean, String> {
        if (!audioStreamingConnection.supportsAudioStreaming) {
            return false to "No Audio Streaming Interface"
        }

        val audioFormat =
            audioStreamingConnection.supportedAudioFormat ?: return false to "No Supported Audio Format"

        if (!audioStreamingConnection.hasFormatTypeDescriptor) {
            return false to "No Audio Streaming Format Descriptor"
        }

        val format: AudioStreamingFormatTypeDescriptor = audioStreamingConnection.formatTypeDescriptor

        val channelCount = format.bNrChannels
        val samplingFrequency = format.tSamFreq.firstOrNull() ?: return false to "No Sample Rate"
        val subFrameSize = format.bSubFrameSize
        val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputFramesPerBuffer =
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toInt() ?: 0

        val deviceFD = audioStreamingConnection.deviceFD

        return if (connectUsbAudioStreamingNative(
                deviceFD,
                audioFormat,
                samplingFrequency,
                subFrameSize,
                channelCount,
                AudioTrack.PERFORMANCE_MODE_LOW_LATENCY,
                outputFramesPerBuffer,
            )
        ) {
            true to "Success"
        } else {
            false to "Native audio player failure. Check logs for errors."
        }
    }

    private external fun connectUsbAudioStreamingNative(
        deviceFD: Int,
        jAudioFormat: Int,
        samplingFrequency: Int,
        subFrameSize: Int,
        channelCount: Int,
        jAudioPerfMode: Int,
        outputFramesPerBuffer: Int,
    ): Boolean

    external fun disconnectUsbAudioStreamingNative()

    external fun startUsbAudioStreamingNative()

    external fun stopUsbAudioStreamingNative()

    fun connectUsbVideoStreaming(
        videoStreamingConnection: VideoStreamingConnection,
        surface: Surface,
        frameFormat: VideoFormat?,
    ): Pair<Boolean, String> {
        val videoFormat = frameFormat ?: return false to "No supported video format"
        val deviceFD = videoStreamingConnection.deviceFD
        return if (connectUsbVideoStreamingNative(
                deviceFD,
                videoFormat.width,
                videoFormat.height,
                videoFormat.fps,
                videoFormat.toLibuvcFrameFormat().ordinal,
                surface,
            )
        ) {
            true to "Success"
        } else {
            false to "Native video player failure. Check logs for errors."
        }
    }

    external fun connectUsbVideoStreamingNative(
        deviceFD: Int,
        width: Int,
        height: Int,
        fps: Int,
        libuvcFrameFormat: Int,
        surface: Surface,
    ): Boolean

    external fun startUsbVideoStreamingNative(): Boolean
    external fun stopUsbVideoStreamingNative()
    external fun disconnectUsbVideoStreamingNative()
    external fun streamingStatsSummaryString(): String
    external fun setZebraVisible(visible: Boolean)

    /**
     * Kotlin implementation of dynamic zebra effect to reduce CPU load on C++ thread if needed,
     * or to allow for easier experimentation.
     */
    @JvmStatic
    fun applyDynamicZebra(
        pixels: ByteBuffer,
        width: Int,
        height: Int,
        stride: Int,
        frameCount: Long
    ) {
        val stripeWidth = 8
        val stripePeriod = 16
        val mask = stripePeriod - 1
        val threshold100 = 215
        val threshold70 = 180
        val offset = (frameCount and mask.toLong()).toInt()

        pixels.order(ByteOrder.nativeOrder())
        val intBuffer = pixels.asIntBuffer()
        
        // Use a temporary array for faster row-based access if needed, 
        // but DirectByteBuffer.asIntBuffer() is generally quite fast.
        val row = IntArray(width)

        for (y in 0 until height) {
            val rowStart = y * stride
            intBuffer.position(rowStart)
            intBuffer.get(row)
            
            var rowModified = false
            for (x in 0 until width) {
                val pixel = row[x]
                // RGBA (Android order is usually R, G, B, A in bytes, so R is lowest byte)
                val r = pixel and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = (pixel shr 16) and 0xFF

                // Luma calculation (BT.601)
                val luma = (77 * r + 150 * g + 29 * b) shr 8

                if (luma >= threshold70) {
                    val stripe = (x - y + offset) and mask
                    if (stripe < stripeWidth) {
                        if (luma >= threshold100) {
                            // 100%+ -> Red (RGBA: 0xFF0000FF)
                            row[x] = 0xFF0000FF.toInt()
                        } else {
                            // 70%-100% -> Green (RGBA: 0xFF00FF00)
                            row[x] = 0xFF00FF00.toInt()
                        }
                        rowModified = true
                    }
                }
            }
            if (rowModified) {
                intBuffer.position(rowStart)
                intBuffer.put(row)
            }
        }
    }
}

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
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import com.nano71.cameramonitor.core.connection.AudioStreamingConnection
import com.nano71.cameramonitor.core.connection.AudioStreamingFormatTypeDescriptor
import com.nano71.cameramonitor.core.connection.VideoFormat
import com.nano71.cameramonitor.core.connection.VideoStreamingConnection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

enum class UsbSpeed {
    Unknown,
    Low,
    Full,
    High,
    Super,
    SuperPlus,
}

object UsbVideoNativeLibrary {
    fun getUsbSpeed(): UsbSpeed {
        return UsbSpeed.entries[getUsbDeviceSpeed()]
    }

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

    external fun getUsbDeviceSpeed(): Int

    external fun disconnectUsbAudioStreamingNative()

    external fun startUsbAudioStreamingNative()

    external fun stopUsbAudioStreamingNative()

    fun connectUsbVideoStreaming(
        videoStreamingConnection: VideoStreamingConnection,
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
    ): Boolean

    external fun startUsbVideoStreamingNative(): Boolean
    external fun stopUsbVideoStreamingNative()
    external fun disconnectUsbVideoStreamingNative()
    external fun streamingStatsSummaryString(): String
    external fun getVideoFormat(): Int

    @JvmStatic
    external fun updateTextures(texY: Int, texUV: Int): Boolean

    class VideoRenderer(private val context: Context) : GLSurfaceView.Renderer {
        private var programNV12 = 0
        private var programRGBA = 0

        private var texY = 0
        private var texUV = 0

        var showZebra = false
        private val startTime = SystemClock.uptimeMillis()

        private lateinit var vertexBuffer: FloatBuffer
        private lateinit var texCoordBuffer: FloatBuffer

        private val mvpMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

        private val vertices = floatArrayOf(
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f, 1.0f,
            1.0f, 1.0f
        )

        private val texCoords = floatArrayOf(
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
        )

        override fun onSurfaceCreated(unused: GL10, p1: EGLConfig) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            texY = createTexture()
            texUV = createTexture()

            vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(vertices)
            vertexBuffer.position(0)

            texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(texCoords)
            texCoordBuffer.position(0)

            initShaders()
        }

        private fun initShaders() {
            val vertexShaderCode = loadShaderFromAssets("shaders/video_v.glsl")
            val fragmentShaderNV12Code = loadShaderFromAssets("shaders/video_nv12_f.glsl")
            val fragmentShaderRGBACode = loadShaderFromAssets("shaders/video_rgba_f.glsl")

            programNV12 = createProgram(vertexShaderCode, fragmentShaderNV12Code)
            programRGBA = createProgram(vertexShaderCode, fragmentShaderRGBACode)
        }

        private fun loadShaderFromAssets(fileName: String): String {
            return context.assets.open(fileName).bufferedReader().use { it.readText() }
        }

        private fun createProgram(vSource: String, fSource: String): Int {
            val vShader = loadShader(GLES30.GL_VERTEX_SHADER, vSource)
            val fShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fSource)
            val program = GLES30.glCreateProgram()
            GLES30.glAttachShader(program, vShader)
            GLES30.glAttachShader(program, fShader)
            GLES30.glLinkProgram(program)
            return program
        }

        private fun loadShader(type: Int, shaderCode: String): Int {
            return GLES30.glCreateShader(type).also { shader ->
                GLES30.glShaderSource(shader, shaderCode)
                GLES30.glCompileShader(shader)
            }
        }

        override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
            GLES30.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(unused: GL10?) {
            // Attempt to update textures. If false, we still draw the last frame data
            // to avoid flickering (skipping draw or clearing to black).
            updateTextures(texY, texUV)

            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            val time = (SystemClock.uptimeMillis() - startTime).toFloat()

            val format = getVideoFormat()
            if (format == 1) { // NV12
                drawNV12(time)
            } else { // RGBA or others treated as RGBA
                drawRGBA(time)
            }
        }

        private fun drawNV12(time: Float) {
            GLES30.glUseProgram(programNV12)

            val positionHandle = GLES30.glGetAttribLocation(programNV12, "aPosition")
            GLES30.glEnableVertexAttribArray(positionHandle)
            GLES30.glVertexAttribPointer(positionHandle, 2, GLES30.GL_FLOAT, false, 8, vertexBuffer)

            val texCoordHandle = GLES30.glGetAttribLocation(programNV12, "aTexCoord")
            GLES30.glEnableVertexAttribArray(texCoordHandle)
            GLES30.glVertexAttribPointer(texCoordHandle, 2, GLES30.GL_FLOAT, false, 8, texCoordBuffer)

            val mvpHandle = GLES30.glGetUniformLocation(programNV12, "uMVPMatrix")
            GLES30.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

            val timeHandle = GLES30.glGetUniformLocation(programNV12, "uTime")
            GLES30.glUniform1f(timeHandle, time)

            val zebraHandle = GLES30.glGetUniformLocation(programNV12, "uShowZebra")
            GLES30.glUniform1i(zebraHandle, if (showZebra) 1 else 0)

            val texYHandle = GLES30.glGetUniformLocation(programNV12, "uTextureY")
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texY)
            GLES30.glUniform1i(texYHandle, 0)

            val texUVHandle = GLES30.glGetUniformLocation(programNV12, "uTextureUV")
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texUV)
            GLES30.glUniform1i(texUVHandle, 1)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            GLES30.glDisableVertexAttribArray(positionHandle)
            GLES30.glDisableVertexAttribArray(texCoordHandle)
        }

        private fun drawRGBA(time: Float) {
            GLES30.glUseProgram(programRGBA)

            val positionHandle = GLES30.glGetAttribLocation(programRGBA, "aPosition")
            GLES30.glEnableVertexAttribArray(positionHandle)
            GLES30.glVertexAttribPointer(positionHandle, 2, GLES30.GL_FLOAT, false, 8, vertexBuffer)

            val texCoordHandle = GLES30.glGetAttribLocation(programRGBA, "aTexCoord")
            GLES30.glEnableVertexAttribArray(texCoordHandle)
            GLES30.glVertexAttribPointer(texCoordHandle, 2, GLES30.GL_FLOAT, false, 8, texCoordBuffer)

            val mvpHandle = GLES30.glGetUniformLocation(programRGBA, "uMVPMatrix")
            GLES30.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

            val timeHandle = GLES30.glGetUniformLocation(programRGBA, "uTime")
            GLES30.glUniform1f(timeHandle, time)

            val zebraHandle = GLES30.glGetUniformLocation(programRGBA, "uShowZebra")
            GLES30.glUniform1i(zebraHandle, if (showZebra) 1 else 0)

            val texRGBAHandle = GLES30.glGetUniformLocation(programRGBA, "uTextureRGBA")
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texY)
            GLES30.glUniform1i(texRGBAHandle, 0)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            GLES30.glDisableVertexAttribArray(positionHandle)
            GLES30.glDisableVertexAttribArray(texCoordHandle)
        }

        private fun createTexture(): Int {
            val tex = IntArray(1)
            GLES30.glGenTextures(1, tex, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            return tex[0]
        }
    }
}

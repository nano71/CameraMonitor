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
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
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

    init {
        System.loadLibrary("usbvideo")
    }

    fun getUsbSpeed(): UsbSpeed = UsbSpeed.entries[getUsbDeviceSpeed()]

    private fun getUsbDeviceSpeed(): Int {
        return 1
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

    class VideoRenderer : GLSurfaceView.Renderer {
        private var programNV12 = 0
        private var programRGBA = 0

        private var texY = 0
        private var texUV = 0

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

        override fun onSurfaceCreated(p0: GL10, p1: EGLConfig) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
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
            val vertexShaderCode = """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                uniform mat4 uMVPMatrix;
                void main() {
                    gl_Position = uMVPMatrix * aPosition;
                    vTexCoord = aTexCoord;
                }
            """.trimIndent()

            val fragmentShaderNV12Code = """
                precision mediump float;
                varying vec2 vTexCoord;
                uniform sampler2D uTextureY;
                uniform sampler2D uTextureUV;
                void main() {
                    float y = texture2D(uTextureY, vTexCoord).r;
                    vec4 uv = texture2D(uTextureUV, vTexCoord);
                    float u = uv.r - 0.5;
                    float v = uv.a - 0.5;
                    float r = y + 1.402 * v;
                    float g = y - 0.34414 * u - 0.71414 * v;
                    float b = y + 1.772 * u;
                    gl_FragColor = vec4(r, g, b, 1.0);
                }
            """.trimIndent()

            val fragmentShaderRGBACode = """
                precision mediump float;
                varying vec2 vTexCoord;
                uniform sampler2D uTextureRGBA;
                void main() {
                    gl_FragColor = texture2D(uTextureRGBA, vTexCoord);
                }
            """.trimIndent()

            programNV12 = createProgram(vertexShaderCode, fragmentShaderNV12Code)
            programRGBA = createProgram(vertexShaderCode, fragmentShaderRGBACode)
        }

        private fun createProgram(vSource: String, fSource: String): Int {
            val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vSource)
            val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fSource)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vShader)
            GLES20.glAttachShader(program, fShader)
            GLES20.glLinkProgram(program)
            return program
        }

        private fun loadShader(type: Int, shaderCode: String): Int {
            return GLES20.glCreateShader(type).also { shader ->
                GLES20.glShaderSource(shader, shaderCode)
                GLES20.glCompileShader(shader)
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            // Attempt to update textures. If false, we still draw the last frame data
            // to avoid flickering (skipping draw or clearing to black).
            updateTextures(texY, texUV)

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val format = getVideoFormat()
            if (format == 1) { // NV12
                drawNV12()
            } else { // RGBA or others treated as RGBA
                drawRGBA()
            }
        }

        private fun drawNV12() {
            GLES20.glUseProgram(programNV12)

            val positionHandle = GLES20.glGetAttribLocation(programNV12, "aPosition")
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)

            val texCoordHandle = GLES20.glGetAttribLocation(programNV12, "aTexCoord")
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)

            val mvpHandle = GLES20.glGetUniformLocation(programNV12, "uMVPMatrix")
            GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

            val texYHandle = GLES20.glGetUniformLocation(programNV12, "uTextureY")
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texY)
            GLES20.glUniform1i(texYHandle, 0)

            val texUVHandle = GLES20.glGetUniformLocation(programNV12, "uTextureUV")
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texUV)
            GLES20.glUniform1i(texUVHandle, 1)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
        }

        private fun drawRGBA() {
            GLES20.glUseProgram(programRGBA)

            val positionHandle = GLES20.glGetAttribLocation(programRGBA, "aPosition")
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)

            val texCoordHandle = GLES20.glGetAttribLocation(programRGBA, "aTexCoord")
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)

            val mvpHandle = GLES20.glGetUniformLocation(programRGBA, "uMVPMatrix")
            GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

            val texRGBAHandle = GLES20.glGetUniformLocation(programRGBA, "uTextureRGBA")
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texY)
            GLES20.glUniform1i(texRGBAHandle, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
        }

        private fun createTexture(): Int {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return tex[0]
        }
    }
}

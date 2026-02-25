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
package com.meta.usbvideo.permission

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combineTransform

sealed interface PermissionUiAction

object RequestCameraPermission : PermissionUiAction

object RequestRecordAudioPermission : PermissionUiAction

class PermissionsViewModel : ViewModel() {
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var recordAudioPermissionLauncher: ActivityResultLauncher<String>

    private val cameraPermissionInternalState: MutableStateFlow<CameraPermissionState> =
        MutableStateFlow(CameraPermissionRequired)
    val cameraPermissionStateFlow: StateFlow<CameraPermissionState> =
        cameraPermissionInternalState.asStateFlow()

    private val recordAudioPermissionInternalState: MutableStateFlow<RecordAudioPermissionState> =
        MutableStateFlow(RecordAudioPermissionRequired)
    val recordAudioPermissionStateFlow: StateFlow<RecordAudioPermissionState> =
        recordAudioPermissionInternalState.asStateFlow()

    fun preparePermissionLaunchers(activity: ComponentActivity) {
        refreshPermissionState(activity)
        cameraPermissionLauncher =
            activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                updateCameraPermissionState(
                    activity.getPermissionStatus(Manifest.permission.CAMERA).toCameraState()
                )
            }
        recordAudioPermissionLauncher =
            activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                updateRecordAudioPermissionState(
                    activity.getPermissionStatus(Manifest.permission.RECORD_AUDIO).toRecordAudioState()
                )
            }
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    recordAudioPermissionLauncher.unregister()
                    cameraPermissionLauncher.unregister()
                }
            })
    }

    fun refreshPermissionState(activity: ComponentActivity) {
        updateCameraPermissionState(
            activity.getPermissionStatus(Manifest.permission.CAMERA).toCameraState()
        )
        updateRecordAudioPermissionState(
            activity.getPermissionStatus(Manifest.permission.RECORD_AUDIO).toRecordAudioState()
        )
    }

    fun uiActionFlow(): Flow<PermissionUiAction> {
        return combineTransform(cameraPermissionStateFlow, recordAudioPermissionStateFlow) {
                cameraPermissionState: CameraPermissionState,
                recordAudioPermissionState: RecordAudioPermissionState,
            ->
            when {
                cameraPermissionState is CameraPermissionRequired -> emit(RequestCameraPermission)
                cameraPermissionState is CameraPermissionRequested -> Unit
                recordAudioPermissionState is RecordAudioPermissionRequired -> emit(RequestRecordAudioPermission)
                recordAudioPermissionState is RecordAudioPermissionRequested -> Unit
                else -> Unit
            }
        }
    }

    fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        updateCameraPermissionState(CameraPermissionRequested)
    }

    fun requestRecordAudioPermission() {
        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        updateRecordAudioPermissionState(RecordAudioPermissionRequested)
    }

    private fun updateCameraPermissionState(cameraPermission: CameraPermissionState) {
        cameraPermissionInternalState.value = cameraPermission
    }

    private fun updateRecordAudioPermissionState(recordAudioPermission: RecordAudioPermissionState) {
        recordAudioPermissionInternalState.value = recordAudioPermission
    }
}

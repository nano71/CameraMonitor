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
package com.meta.usbvideo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.meta.usbvideo.permission.PermissionsViewModel
import com.meta.usbvideo.viewHolder.StatusScreenViewHolder
import com.meta.usbvideo.viewHolder.StreamingViewHolder
import com.meta.usbvideo.viewModel.StreamerViewModel


class StreamerScreensAdapter(
    val lifecycleOwner: LifecycleOwner,
    private val streamerViewModel: StreamerViewModel,
    private val permissionsViewModel: PermissionsViewModel,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var screens: List<StreamerScreen> = listOf(StreamerScreen.Status)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (StreamerScreen.entries[viewType]) {
            StreamerScreen.Status ->
                StatusScreenViewHolder(
                    parent.inflate(R.layout.status_screen),
                    streamerViewModel,
                    permissionsViewModel,
                )

            StreamerScreen.Streaming ->
                StreamingViewHolder(
                    parent.inflate(R.layout.streaming_screen),
                    streamerViewModel,
                )
        }
    }

    fun ViewGroup.inflate(@LayoutRes layoutRes: Int): View =
        LayoutInflater.from(context).inflate(layoutRes, this, false)

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is StatusScreenViewHolder -> holder.observeViewModel(lifecycleOwner)
            is StreamingViewHolder -> Unit
        }
    }

    override fun getItemCount(): Int {
        return screens.size
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getItemViewType(position: Int): Int {
        return screens[position].ordinal
    }
}

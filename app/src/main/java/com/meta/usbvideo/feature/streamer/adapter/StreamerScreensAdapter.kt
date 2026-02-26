package com.meta.usbvideo.feature.streamer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.meta.usbvideo.R
import com.meta.usbvideo.feature.streamer.StreamerScreen
import com.meta.usbvideo.feature.streamer.StreamerViewModel
import com.meta.usbvideo.feature.streamer.viewholder.StatusScreenViewHolder
import com.meta.usbvideo.feature.streamer.viewholder.StreamingViewHolder

class StreamerScreensAdapter(
    val lifecycleOwner: LifecycleOwner,
    private val streamerViewModel: StreamerViewModel,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var screens: List<StreamerScreen> = listOf(StreamerScreen.Status)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (StreamerScreen.entries[viewType]) {
            StreamerScreen.Status ->
                StatusScreenViewHolder(
                    parent.inflate(R.layout.status_screen),
                    streamerViewModel,
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
            is StatusScreenViewHolder -> holder.observeViewModel(lifecycleOwner, streamerViewModel)
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
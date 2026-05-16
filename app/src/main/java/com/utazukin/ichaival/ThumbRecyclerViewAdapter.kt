/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2026 Utazukin
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.utazukin.ichaival

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.load
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Disposable
import coil3.request.allowRgb565
import coil3.request.crossfade
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.launch
import kotlin.math.min

class ThumbRecyclerViewAdapter(
    fragment: Fragment,
    private val archive: Archive)
    : RecyclerView.Adapter<ThumbRecyclerViewAdapter.ViewHolder>() {

    private val listener = fragment as? ThumbInteractionListener ?: fragment.activity as? ThumbInteractionListener
    private val scope = fragment.lifecycleScope
    private val defaultHeight = fragment.resources.getDimension(R.dimen.thumb_preview_size).toInt()
    private val thumbIds = (0 until archive.numPages).toMutableList()
    private var extractedThumbs = mutableListOf<Int>()
    private val loader = fragment.requireContext().imageLoader.newBuilder()
        .components {
            add(
                    OkHttpNetworkFetcherFactory(
                            callFactory = WebHandler.httpClient.newBuilder().addInterceptor(ThumbHttpInterceptor(scope, WebHandler.httpClient))
                                .build()
                    )
            )
        }.build()

    private val onClickListener = View.OnClickListener { v ->
        val item = v.getTag(R.id.small_thumb) as Int
        listener?.onThumbSelection(item)
    }

    private val onLongPressListener = View.OnLongClickListener {
        val item = it.getTag(R.id.small_thumb) as Int
        listener?.onThumbLongPress(item) ?: false
    }

    val firstThumb
        get() = thumbIds[0]
    private val imageLoadRequests: MutableMap<ViewHolder, Disposable> = mutableMapOf()
    private val generateJob: Job

    init {
        setHasStableIds(true)
        generateJob = scope.launch {
            var thumbFlow = WebHandler.generateThumbs(archive.id, archive.numPages)
            if (thumbFlow == null) {
                for (i in 0 until 3) {
                    delay(500)
                    thumbFlow = WebHandler.generateThumbs(archive.id, archive.numPages)
                    if (thumbFlow != null)
                        break
                }
            }

            thumbFlow?.cancellable()?.collect {
                val thumbId = thumbIds.indexOf(it)
                if (thumbId >= 0)
                    notifyItemChanged(thumbId)

                extractedThumbs.add(it)
                if (extractedThumbs.size == archive.numPages)
                    generateJob.cancel()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun useSubset(start: Int, end: Int? = null) {
        val end = end ?: archive.numPages
        thumbIds.clear()
        thumbIds.addAll(start until end)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.thumb_layout, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = min(thumbIds.size, archive.numPages)
    override fun getItemId(position: Int) = position.toLong()

    override fun onBindViewHolder(holder: ViewHolder, index: Int) {
        holder.pageNumView.text = (thumbIds[index] + 1).toString()

        with(holder.thumbView) {
            setTag(R.id.small_thumb, thumbIds[index])
            setOnClickListener(onClickListener)
            setOnLongClickListener(onLongPressListener)
        }

        if (thumbIds[index] in extractedThumbs) {
            val image = archive.getThumb(thumbIds[index])
            imageLoadRequests[holder] = holder.thumbView.load(image, loader) {
                addAuthHeader()
                allowRgb565(true)
                crossfade(true)
                size(defaultHeight)
                listener { _, _ ->
                    with(holder.thumbView) {
                        updateLayoutParams { height = RelativeLayout.LayoutParams.WRAP_CONTENT }
                        adjustViewBounds = true
                    }
                }
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        imageLoadRequests.remove(holder)?.dispose()
        super.onViewRecycled(holder)

        with(holder.thumbView) {
            adjustViewBounds = false
            updateLayoutParams { height = defaultHeight }
        }
    }

    interface ThumbInteractionListener {
        fun onThumbSelection(page: Int)
        fun onThumbLongPress(page: Int) : Boolean
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbView: ImageView = view.findViewById(R.id.small_thumb)
        val pageNumView: TextView = view.findViewById(R.id.page_num)
    }
}
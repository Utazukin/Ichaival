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
import coil3.dispose
import coil3.imageLoader
import coil3.load
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ThumbRecyclerViewAdapter(
    fragment: Fragment,
    thumbResult: ThumbResult,
    private val archive: MetaArchive)
    : RecyclerView.Adapter<ThumbRecyclerViewAdapter.ViewHolder>() {

    private val listener = fragment as? ThumbInteractionListener ?: fragment.activity as? ThumbInteractionListener
    private val defaultHeight = fragment.resources.getDimension(R.dimen.thumb_preview_size).toInt()
    var thumbStart = -1
        private set
    private var thumbEnd = -1
    private val extractedThumbs: BooleanArray
    private val loader = fragment.requireContext().imageLoader.newBuilder()
        .components {
            add(
                    OkHttpNetworkFetcherFactory(
                            callFactory = WebHandler.httpClient.newBuilder()
                                .addInterceptor(ThumbHttpInterceptor(fragment.lifecycleScope, WebHandler.httpClient))
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

    init {
        setHasStableIds(true)
        when (thumbResult) {
            is CompleteThumbResult -> extractedThumbs = BooleanArray(archive.numPages) { true }
            is FailedThumbResult -> extractedThumbs = BooleanArray(archive.numPages)
            is InProgressThumbResult -> {
                extractedThumbs = BooleanArray(archive.numPages)
                fragment.lifecycleScope.launch {
                    thumbResult.flow.collect {
                        extractedThumbs[it] = true

                        if (thumbStart >= 0 && thumbEnd >= 0 && it in thumbStart until thumbEnd)
                            notifyItemChanged(it - thumbStart)
                    }
                }
            }
        }
    }

    fun useSubset(start: Int, end: Int? = null) {
        val end = end ?: archive.numPages
        if (thumbStart == start && end == thumbEnd)
            return

        val oldSize = thumbEnd - thumbStart
        val newSize = end - start
        thumbStart = start
        thumbEnd = end

        if (oldSize <= 0) {
            notifyItemRangeInserted(0, thumbEnd)
        } else {
            notifyItemRangeChanged(0, min(end - start, oldSize))

            val diff = newSize - oldSize
            if (diff < 0)
                notifyItemRangeRemoved(newSize, abs(diff))
            else
                notifyItemRangeInserted(oldSize, diff)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.thumb_layout, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = max(thumbEnd - thumbStart, 0)
    override fun getItemId(position: Int) = (thumbStart + position).toLong()

    override fun onBindViewHolder(holder: ViewHolder, index: Int) {
        val pageIndex = thumbStart + index
        val pageNum = pageIndex + 1
        holder.pageNumView.text = pageNum.toString()

        with(holder.thumbView) {
            setTag(R.id.small_thumb, pageIndex)
            setOnClickListener(onClickListener)
            setOnLongClickListener(onLongPressListener)
        }

        if (extractedThumbs[pageIndex]) {
            val image = archive.getThumb(pageIndex)
            holder.thumbView.load(image, loader) {
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
        } else {
            holder.thumbView.setImageDrawable(null)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)

        with(holder.thumbView) {
            dispose()
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
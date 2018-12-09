/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2018 Utazukin
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

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.*

class ThumbRecyclerViewAdapter(
    private val listener: ThumbInteractionListener?,
    private val glide: RequestManager,
    private val scope: CoroutineScope,
    private val archive: Archive)
    : RecyclerView.Adapter<ThumbRecyclerViewAdapter.ViewHolder>() {

    private val onClickListener: View.OnClickListener
    private var maxThumbnails = 10
    val hasMorePreviews: Boolean
        get() = maxThumbnails < archive.numPages
    private val imageLoadingJobs: MutableMap<ViewHolder, Job> = mutableMapOf()

    init {
        onClickListener = View.OnClickListener { v ->
            val item = v.getTag(R.id.small_thumb) as Int
            listener?.onThumbSelection(item)
        }
        scope.launch(Dispatchers.Main) {
            launch(Dispatchers.IO) { archive.loadImageUrls() }.join()
            notifyDataSetChanged()
        }
    }

    fun increasePreviewCount() {
        if (maxThumbnails < archive.numPages) {
            val currentCount = itemCount
            maxThumbnails *= 2
            notifyItemRangeInserted(currentCount + 1, Math.min(maxThumbnails, archive.numPages))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.thumb_layout, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = if (archive.numPages > maxThumbnails) maxThumbnails else archive.numPages

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val page = position
        holder.pageNumView.text = (page + 1).toString()

        val job = scope.launch(Dispatchers.Main) {
            val image = withContext(Dispatchers.Default) { archive.getPageImage(page) }

            with(holder.thumbView) {
                setTag(R.id.small_thumb, page)
                setOnClickListener(onClickListener)
            }

            val options = RequestOptions()
            options.encodeFormat(Bitmap.CompressFormat.JPEG)
            options.encodeQuality(80)
            options.override(getDpAdjusted(200), getDpAdjusted(200))
            glide.load(image)
                .apply(options)
                .addListener(object : RequestListener<Drawable>{
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>?,
                        isFirstResource: Boolean
                    ): Boolean {
                        holder.progressBar.visibility = View.GONE
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable?,
                        model: Any?,
                        target: Target<Drawable>?,
                        dataSource: DataSource?,
                        isFirstResource: Boolean
                    ): Boolean {
                        holder.pageNumView.visibility = View.GONE
                        holder.progressBar.visibility = View.GONE
                        return false
                    }

                }).into(holder.thumbView)
        }
        imageLoadingJobs[holder] = job
    }

    override fun onViewRecycled(holder: ViewHolder) {
        imageLoadingJobs[holder]?.cancel()
        imageLoadingJobs.remove(holder)
        super.onViewRecycled(holder)
    }

    interface ThumbInteractionListener {
        fun onThumbSelection(page: Int)
    }

    inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val thumbView: PhotoView = view.findViewById(R.id.small_thumb)
        val pageNumView: TextView = view.findViewById(R.id.page_num)
        val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
    }
}
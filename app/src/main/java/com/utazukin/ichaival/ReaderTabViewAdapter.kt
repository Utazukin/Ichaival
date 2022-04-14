/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2022 Utazukin
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
import android.widget.TextView
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.signature.ObjectKey
import kotlinx.coroutines.*

class ReaderTabViewAdapter (private val activity: BaseActivity) : PagingDataAdapter<ReaderTab, ReaderTabViewAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val listener = activity as? OnTabInteractionListener
    private val activityScope = activity as CoroutineScope
    private val glideManager = Glide.with(activity)

    private val onClickListener: View.OnClickListener = View.OnClickListener { v ->
        val item = v.tag as ReaderTab
        listener?.onTabInteraction(item)
    }
    private val onLongClickListener: View.OnLongClickListener = View.OnLongClickListener {
        val item = it.tag as ReaderTab
        listener?.onLongPressTab(item) == true
    }

    private val jobs: MutableMap<ViewHolder, Job> = mutableMapOf()

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        getItem(position)?.let { item ->
            holder.titleView.text = item.title
            holder.pageView.text = (item.page + 1).toString()
            jobs[holder] = activityScope.launch {
                val (thumbPath, modifiedTime) = withContext(Dispatchers.Default) {
                    DatabaseReader.getArchiveImage(item.id, activity)
                }
                thumbPath?.let {
                    glideManager
                        .load(it)
                        .signature(ObjectKey(modifiedTime))
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(holder.thumbView)
                }
            }

            with(holder.view) {
                tag = item
                setOnClickListener(onClickListener)
                setOnLongClickListener(onLongClickListener)
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        jobs[holder]?.cancel()
        jobs.remove(holder)
        holder.thumbView.setImageBitmap(null)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.reader_tab, parent, false)
        return ViewHolder(view)
    }

    inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val titleView: TextView = view.findViewById(R.id.archive_title)
        val pageView: TextView = view.findViewById(R.id.archive_page)
        val thumbView: ImageView = view.findViewById(R.id.reader_thumb)
    }

    interface OnTabInteractionListener {
        fun onTabInteraction(tab: ReaderTab)

        fun onLongPressTab(tab: ReaderTab) : Boolean
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ReaderTab>() {
            override fun areItemsTheSame(oldItem: ReaderTab, newItem: ReaderTab) = oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ReaderTab, newItem: ReaderTab) = oldItem == newItem
        }
    }
}
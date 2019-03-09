/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2019 Utazukin
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

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import kotlinx.coroutines.*

class ReaderTabViewAdapter (
    private val listener: OnTabInteractionListener?,
    private val activityScope: CoroutineScope,
    private val glideManager: RequestManager
) : RecyclerView.Adapter<ReaderTabViewAdapter.ViewHolder>(), ReaderTabListener {

    override fun getItemCount() = ReaderTabHolder.tabs.size

    private val onClickListener: View.OnClickListener

    private val onLongClickListener: View.OnLongClickListener

    private val jobs: MutableMap<ViewHolder, Job> = mutableMapOf()

    init {
        onClickListener = View.OnClickListener { v ->
            val item = v.tag as ReaderTab
            listener?.onTabInteraction(item, false)
        }

        onLongClickListener = View.OnLongClickListener { v ->
            val item = v.tag as ReaderTab
            listener?.onTabInteraction(item, true)
            true
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = ReaderTabHolder.tabs[position]
        holder.titleView.text = item.title
        holder.pageView.text = (item.page + 1).toString()
        jobs[holder] = activityScope.launch {
            val thumb = withContext(Dispatchers.Default) {
                val context = activityScope as Context
                DatabaseReader.getArchiveImage(item.id, context.filesDir)
            }
            glideManager
                .load(thumb)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(holder.thumbView)
        }

        with(holder.view) {
            tag = item
            setOnClickListener(onClickListener)
            setOnLongClickListener(onLongClickListener)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        jobs[holder]?.cancel()
        jobs.remove(holder)
    }

    override fun onTabsRestored() {
        notifyDataSetChanged()
    }

    override fun onTabAdded(index: Int, id: String) {
        notifyItemInserted(index)
    }

    override fun onTabRemoved(index: Int, id: String) {
        notifyItemRemoved(index)
    }

    override fun onTabChanged(index: Int) {
        notifyItemChanged(index)
    }

    override fun onTabsCleared(oldSize: Int) {
        notifyItemRangeRemoved(0, oldSize)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.reader_tab, parent, false)
        return ViewHolder(view)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        ReaderTabHolder.registerListener(this)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        ReaderTabHolder.unregisterListener(this)
    }

    inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val titleView: TextView = view.findViewById(R.id.archive_title)
        val pageView: TextView = view.findViewById(R.id.archive_page)
        val thumbView: ImageView = view.findViewById(R.id.reader_thumb)
    }

    interface OnTabInteractionListener {
        fun onTabInteraction(tab: ReaderTab, longPress: Boolean)
    }
}
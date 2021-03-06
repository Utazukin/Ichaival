/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2021 Utazukin
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
import androidx.cardview.widget.CardView
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import com.utazukin.ichaival.ArchiveListFragment.OnListFragmentInteractionListener
import kotlinx.coroutines.*

class ArchiveRecyclerViewAdapter(
    private val mListener: OnListFragmentInteractionListener?,
    private val longListener: ((a: Archive) -> Boolean)?,
    private val scope: CoroutineScope,
    private val glideManager: RequestManager
) : PagedListAdapter<Archive, ArchiveRecyclerViewAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val mOnClickListener: View.OnClickListener = View.OnClickListener { v ->
        val item = v.tag as Archive
        // Notify the active callbacks interface (the activity, if the fragment is attached to
        // one) that an item has been selected.
        mListener?.onListFragmentInteraction(item)
    }

    private val onLongClickListener: View.OnLongClickListener = View.OnLongClickListener { v ->
        val item = v.tag as Archive
        longListener?.invoke(item) == true
    }

    private val thumbLoadingJobs = mutableMapOf<ViewHolder, Job>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.fragment_archive, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        getItem(position)?.let {
            holder.archiveName.text = it.title
            val job = scope.launch(Dispatchers.Main) {
                val image = withContext(Dispatchers.Default) {
                    DatabaseReader.getArchiveImage(
                        it,
                        holder.mContentView.context)
                }
                glideManager.load(image).into(holder.archiveImage)
            }
            thumbLoadingJobs[holder] = job

            with(holder.mView) {
                tag = it
                setOnClickListener(mOnClickListener)
                setOnLongClickListener(onLongClickListener)
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        thumbLoadingJobs[holder]?.cancel()
        thumbLoadingJobs.remove(holder)
        holder.archiveImage.setImageBitmap(null)
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(val mView: View) : RecyclerView.ViewHolder(mView) {
        val mContentView: CardView = mView.findViewById(R.id.archive_card)
        val archiveName: TextView = mContentView.findViewById(R.id.archive_label)
        val archiveImage: ImageView = mContentView.findViewById(R.id.archive_thumb)

        override fun toString(): String {
            return super.toString() + " '" + archiveName + "'"
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Archive>() {
            override fun areItemsTheSame(oldItem: Archive, newItem: Archive) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Archive, newItem: Archive) = oldItem == newItem
        }
    }
}

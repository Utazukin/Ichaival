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


import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.utazukin.ichaival.ArchiveListFragment.OnListFragmentInteractionListener
import kotlinx.android.synthetic.main.fragment_archive.view.*
import kotlinx.coroutines.*

class ArchiveRecyclerViewAdapter(
    private val mListener: OnListFragmentInteractionListener?,
    private val scope: CoroutineScope
) : RecyclerView.Adapter<ArchiveRecyclerViewAdapter.ViewHolder>() {

    private val mOnClickListener: View.OnClickListener

    private val onLongClickListener: View.OnLongClickListener

    private var mValuesCopy: List<Archive>

    private val mValues: MutableList<Archive> = mutableListOf()

    private val thumbLoadingJobs = mutableMapOf<ViewHolder, Job>()

    init {
        mOnClickListener = View.OnClickListener { v ->
            val item = v.tag as Archive
            // Notify the active callbacks interface (the activity, if the fragment is attached to
            // one) that an item has been selected.
            mListener?.onListFragmentInteraction(item)
        }

        onLongClickListener = View.OnLongClickListener { v ->
            val item = v.tag as Archive
            mListener?.onFragmentLongPress(item, v) == true
        }

        mValuesCopy = mValues.toList()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.fragment_archive, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = mValues[position]
        holder.archiveName.text = item.title
        val job = scope.launch(Dispatchers.Main) {
            val image = withContext(Dispatchers.Default) { DatabaseReader.getArchiveImage(item, holder.mContentView.context.filesDir)}
            holder.archiveImage.setImageBitmap(image)
        }
        thumbLoadingJobs[holder] = job

        with(holder.mView) {
            tag = item
            setOnClickListener(mOnClickListener)
            setOnLongClickListener(onLongClickListener)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        thumbLoadingJobs[holder]?.cancel()
        thumbLoadingJobs.remove(holder)
        holder.archiveImage.setImageBitmap(null)
        super.onViewRecycled(holder)
    }

    fun updateDataCopy(list: List<Archive>) {
        mValues.clear()
        mValues.addAll(list)
        mValuesCopy = mValues.toList()
        notifyDataSetChanged()
    }

    fun getRandomArchive() : Archive? {
        return if (mValues.any()) mValues.random() else null
    }

    fun filter(filter: String?) {
        if (filter == null)
            return

        mValues.clear()
        if (filter.isEmpty())
            mValues.addAll(mValuesCopy)
        else {
            val normalized = filter.toLowerCase()
            for (archive in mValuesCopy) {
                if (archive.title.toLowerCase().contains(normalized))
                    mValues.add(archive)
                else {
                   val terms = filter.split(Regex("\\s"))
                    for (term in terms) {
                        if (archive.containsTag(term))
                            mValues.add(archive)
                    }
                }
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = mValues.size

    inner class ViewHolder(val mView: View) : RecyclerView.ViewHolder(mView) {
        val mContentView: CardView = mView.archive_card
        val archiveName: TextView = mContentView.findViewById(R.id.archive_label)
        val archiveImage: ImageView = mContentView.findViewById(R.id.archive_thumb)

        override fun toString(): String {
            return super.toString() + " '" + archiveName + "'"
        }
    }
}
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


import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import com.utazukin.ichaival.ArchiveListFragment.OnListFragmentInteractionListener
import kotlinx.android.synthetic.main.fragment_archive.view.*
import kotlinx.coroutines.*

class ArchiveRecyclerViewAdapter(
    private val mListener: OnListFragmentInteractionListener?,
    private val longListener: ((a: ArchiveBase) -> Boolean)?,
    private val scope: CoroutineScope,
    private val glideManager: RequestManager
) : RecyclerView.Adapter<ArchiveRecyclerViewAdapter.ViewHolder>() {

    private var sortMethod: SortMethod = SortMethod.Alpha
    private var descending = false
    private val mOnClickListener: View.OnClickListener

    private val onLongClickListener: View.OnLongClickListener

    private var mValuesCopy: List<ArchiveBase>

    private val mValues: MutableList<ArchiveBase> = mutableListOf()

    private val thumbLoadingJobs = mutableMapOf<ViewHolder, Job>()

    init {
        mOnClickListener = View.OnClickListener { v ->
            val item = v.tag as ArchiveBase
            // Notify the active callbacks interface (the activity, if the fragment is attached to
            // one) that an item has been selected.
            mListener?.onListFragmentInteraction(item)
        }

        onLongClickListener = View.OnLongClickListener { v ->
            val item = v.tag as ArchiveBase
            longListener?.invoke(item) == true
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
            glideManager.load(image).into(holder.archiveImage)
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

    private fun updateSort(method: SortMethod, desc: Boolean, force: Boolean) {
        if (method != sortMethod || descending != desc || force) {
            sortMethod = method
            descending = desc

            when(method) {
                SortMethod.Alpha -> {
                    if (descending)
                        mValues.sortByDescending { it.title.toLowerCase() }
                    else
                        mValues.sortBy { it.title.toLowerCase() }
                }
                SortMethod.Date -> {
                    if (descending)
                        mValues.sortByDescending { it.dateAdded }
                    else
                        mValues.sortBy { it.dateAdded }
                }
            }
            notifyDataSetChanged()
        }
    }

    fun updateSort(method: SortMethod, descending: Boolean) = updateSort(method, descending, false)

    fun updateDataCopy(list: List<ArchiveBase>) {
        mValues.clear()
        mValues.addAll(list)
        mValuesCopy = mValues.toList()
        updateSort(sortMethod, true)
    }

    fun getRandomArchive() : ArchiveBase? {
        return if (mValues.any()) mValues.random() else null
    }

    fun filter(filter: CharSequence?, onlyNew: Boolean) : Int {
        if (filter == null)
            return mValues.size

        fun addIfNew(archive: ArchiveBase) {
            if (!onlyNew || archive.isNew)
                mValues.add(archive)
        }

        mValues.clear()
        if (filter.isEmpty())
            mValues.addAll(if (onlyNew) mValuesCopy.filter { it.isNew } else mValuesCopy)
        else {
            val normalized = filter.toString().toLowerCase()
            val spaceRegex by lazy { Regex("\\s") }
            for (archive in mValuesCopy) {
                if (archive.title.toLowerCase().contains(normalized) && !mValues.contains(archive))
                    addIfNew(archive)
                else {
                    val terms = filter.split(spaceRegex)
                    var hasAll = true
                    var i = 0
                    while (i < terms.size) {
                        var term = terms[i]
                        val colonIndex = term.indexOf(':')
                        if (term.startsWith("\"")
                            || (colonIndex in 0..(term.length - 2) && term[colonIndex + 1] == '"')) {
                            val builder = StringBuilder(term)
                            if (!term.endsWith("\"")) {
                                var k = i + 1
                                while (k < terms.size && !terms[k].endsWith("\"")) {
                                    builder.append(" ")
                                    builder.append(terms[k])
                                    ++k
                                }

                                if (k < terms.size && terms[k].endsWith("\"")) {
                                    builder.append(" ")
                                    builder.append(terms[k])
                                }
                                i = k
                            }
                            term = builder.removeSurrounding("\"").toString()
                        }

                        val containsTag = archive.containsTag(term.removePrefix("-"))
                        val isNegative = term.startsWith("-")
                        if (containsTag == isNegative) {
                            hasAll = false
                            break
                        }
                        ++i
                    }

                    if (hasAll && !mValues.contains(archive))
                        addIfNew(archive)
                }
            }
        }
        updateSort(sortMethod, descending, true)
        return mValues.size
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

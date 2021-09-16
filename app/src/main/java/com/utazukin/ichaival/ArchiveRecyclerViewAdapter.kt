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


import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import com.google.android.material.color.MaterialColors
import com.utazukin.ichaival.ArchiveListFragment.OnListFragmentInteractionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArchiveRecyclerViewAdapter(
    private val mListener: OnListFragmentInteractionListener?,
    private val longListener: ((a: Archive) -> Boolean)?,
    fragment: Fragment,
    private val glideManager: RequestManager
) : PagedListAdapter<Archive, ArchiveRecyclerViewAdapter.ViewHolder>(DIFF_CALLBACK), ActionMode.Callback {

    private var multiSelect = false
    private val selectedArchives = mutableMapOf<Archive, Int>()
    private var actionMode: ActionMode? = null
    private val scope = fragment.lifecycleScope
    private val context = fragment.requireContext()

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

    fun enableMultiSelect(activity: AppCompatActivity) : Boolean {
        multiSelect = true
        activity.startSupportActionMode(this)
        return true
    }

    fun disableMultiSelect() = actionMode?.finish()

    private fun selectArchive(holder: ViewHolder, archive: Archive, position: Int) {
        if (!selectedArchives.contains(archive)) {
            holder.mContentView.setCardBackgroundColor(ContextCompat.getColor(holder.mContentView.context, R.color.colorPrimaryDark))
            selectedArchives[archive] = position
        } else {
            holder.mContentView.setCardBackgroundColor(MaterialColors.getColor(holder.mContentView, R.attr.cardBackgroundColor))
            selectedArchives.remove(archive)
        }

        actionMode?.title = "${selectedArchives.size} selected"
    }

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

            if (selectedArchives.contains(it))
                holder.mContentView.setCardBackgroundColor(ContextCompat.getColor(holder.mContentView.context, R.color.colorPrimaryDark))
            else
                holder.mContentView.setCardBackgroundColor(MaterialColors.getColor(holder.mContentView, R.attr.cardBackgroundColor))

            with(holder.mView) {
                tag = it
                setOnClickListener { view ->
                    if (!multiSelect)
                        mOnClickListener.onClick(view)
                    else
                        selectArchive(holder, it, position)
                }
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

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        mode?.menuInflater?.inflate(R.menu.archive_select_menu, menu) ?: return false
        actionMode = mode
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = true

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        if (selectedArchives.isEmpty())
            return true

        if (item?.itemId == R.id.delete_select_archive) {
            val builder = AlertDialog.Builder(context).apply {
                setTitle("Delete Archives")
                setMessage(context.resources.getQuantityString(R.plurals.delete_archive_count, selectedArchives.size).format(selectedArchives.size))
                setPositiveButton("Yes") { dialog, _ ->
                    dialog.dismiss()
                    scope.launch(Dispatchers.IO) {
                        val deleted = WebHandler.deleteArchives(selectedArchives.keys.map { it.id }.toList())
                        if (deleted.isNotEmpty())
                            DatabaseReader.deleteArchives(deleted)
                    }
                    mode?.finish()
                }
                setNegativeButton("No") { dialog, _ -> dialog.dismiss() }
            }
            builder.create().show()
        } else if (item?.itemId == R.id.bookmark_select_item) {
            val archives = selectedArchives.keys.toList()
            scope.launch { ReaderTabHolder.addTabs(archives) }
            mode?.finish()
        }
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        multiSelect = false

        for (index in selectedArchives.values)
            notifyItemChanged(index)

        selectedArchives.clear()
        actionMode = null
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Archive>() {
            override fun areItemsTheSame(oldItem: Archive, newItem: Archive) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Archive, newItem: Archive) = oldItem == newItem
        }
    }
}

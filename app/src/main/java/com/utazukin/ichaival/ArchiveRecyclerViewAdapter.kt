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


import android.content.Context
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagedListAdapter
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.color.MaterialColors
import com.utazukin.ichaival.ArchiveListFragment.OnListFragmentInteractionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ListViewType {
    Card,
    Cover;

    companion object {
        fun fromString(context: Context, s: String?) : ListViewType {
            return when(s) {
                context.resources.getString(R.string.cover_view) -> Cover
                else -> Card
            }
        }
    }
}

class ArchiveRecyclerViewAdapter(
    fragment: Fragment,
    private val longListener: ((a: Archive) -> Boolean)?
) : PagedListAdapter<Archive, ArchiveRecyclerViewAdapter.ViewHolder>(DIFF_CALLBACK), ActionMode.Callback {

    private var multiSelect = false
    private val selectedArchives = mutableMapOf<Archive, Int>()
    private var actionMode: ActionMode? = null
    private val scope = fragment.lifecycleScope
    private val context = fragment.requireContext()
    private val fragmentManager = fragment.childFragmentManager
    private val listener = fragment.activity as? OnListFragmentInteractionListener
    private val glideManager = Glide.with(fragment.requireActivity())
    private val listViewType = ListViewType.fromString(context, PreferenceManager.getDefaultSharedPreferences(context).getString(fragment.resources.getString(R.string.archive_list_type_key), ""))

    private val mOnClickListener: View.OnClickListener = View.OnClickListener { v ->
        val item = v.tag as Archive
        // Notify the active callbacks interface (the activity, if the fragment is attached to
        // one) that an item has been selected.
        listener?.onListFragmentInteraction(item)
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
            holder.mContentView?.let { it.setCardBackgroundColor(ContextCompat.getColor(it.context, R.color.colorPrimaryDark)) }
            if (listViewType == ListViewType.Cover)
                holder.archiveName.setBackgroundColor(ContextCompat.getColor(holder.archiveName.context, R.color.colorPrimaryDark))
            selectedArchives[archive] = position
        } else {
            holder.mContentView?.let { it.setCardBackgroundColor(MaterialColors.getColor(it, R.attr.cardBackgroundColor)) }
            if (listViewType == ListViewType.Cover)
                holder.archiveName.setBackgroundColor(ContextCompat.getColor(holder.archiveName.context, R.color.archive_cover_label))
            selectedArchives.remove(archive)
        }

        actionMode?.title = context.getString(R.string.selected_archives, selectedArchives.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = when (listViewType) {
            ListViewType.Cover -> R.layout.fragment_archive_cover
            else -> R.layout.fragment_archive
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        getItem(position)?.let {
            holder.archiveName.text = it.title
            val job = scope.launch(Dispatchers.Main) {
                val image = withContext(Dispatchers.Default) {
                    DatabaseReader.getArchiveImage(it, holder.mView.context)
                }
                image?.let { pair ->
                    val (imagePath, modifiedTime) = pair
                    var builder = glideManager.load(imagePath).format(DecodeFormat.PREFER_RGB_565).transition(DrawableTransitionOptions.withCrossFade()).signature(ObjectKey(modifiedTime))
                    if (listViewType == ListViewType.Cover)
                        builder = builder.transform(StartCrop())
                    builder.into(holder.archiveImage)
                }
            }
            thumbLoadingJobs[holder] = job

            if (listViewType == ListViewType.Card && holder.mContentView != null) {
                if (selectedArchives.contains(it))
                    holder.mContentView.setCardBackgroundColor(ContextCompat.getColor(holder.mContentView.context, R.color.colorPrimaryDark))
                else
                    holder.mContentView.setCardBackgroundColor(MaterialColors.getColor(holder.mContentView, R.attr.cardBackgroundColor))
            } else if (listViewType == ListViewType.Cover) {
                if (selectedArchives.contains(it))
                    holder.archiveName.setBackgroundColor(ContextCompat.getColor(holder.archiveName.context, R.color.colorPrimaryDark))
                else
                    holder.archiveName.setBackgroundColor(ContextCompat.getColor(holder.archiveName.context, R.color.archive_cover_label))
            }

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
        thumbLoadingJobs.remove(holder)?.cancel()
        holder.archiveImage.setImageBitmap(null)
        glideManager.clear(holder.archiveImage)
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(val mView: View) : RecyclerView.ViewHolder(mView) {
        val mContentView: CardView? = mView.findViewById(R.id.archive_card)
        val archiveName: TextView = mView.findViewById(R.id.archive_label)
        val archiveImage: ImageView = mView.findViewById(R.id.archive_thumb)

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

        when (item?.itemId) {
            R.id.delete_select_archive -> {
                val builder = AlertDialog.Builder(context).apply {
                    setTitle(R.string.delete_archive_item)
                    setMessage(context.resources.getQuantityString(R.plurals.delete_archive_count, selectedArchives.size, selectedArchives.size))
                    setPositiveButton(R.string.yes) { dialog, _ ->
                        dialog.dismiss()
                        scope.launch(Dispatchers.IO) {
                            val deleted = WebHandler.deleteArchives(selectedArchives.keys.map { it.id }.toList())
                            if (deleted.isNotEmpty())
                                DatabaseReader.deleteArchives(deleted)
                        }
                        mode?.finish()
                    }
                    setNegativeButton(R.string.no) { dialog, _ -> dialog.dismiss() }
                }
                builder.create().show()
            }
            R.id.bookmark_select_item -> {
                val archives = selectedArchives.keys.toList()
                scope.launch { ReaderTabHolder.addTabs(archives) }
                mode?.finish()
            }
            R.id.category_select_item -> {
                val dialog = AddToCategoryDialogFragment.newInstance(selectedArchives.keys.map { it.id })
                dialog.show(fragmentManager, "add_category")
            }
        }
        return true
    }

    fun onAddedToCategory(category: ArchiveCategory) {
        if (!multiSelect || selectedArchives.none())
            return

        Toast.makeText(context, "Added to ${category.name}.", Toast.LENGTH_SHORT).show()
        actionMode?.finish()
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

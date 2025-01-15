/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2025 Utazukin
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
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.request.transformations
import com.google.android.material.color.MaterialColors
import com.utazukin.ichaival.ArchiveListFragment.OnListFragmentInteractionListener
import com.utazukin.ichaival.database.DatabaseReader
import com.utazukin.ichaival.database.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    viewModel: SearchViewModel,
    private val longListener: ((a: ArchiveBase) -> Boolean)?
) : PagingDataAdapter<ArchiveBase, ArchiveRecyclerViewAdapter.ViewHolder>(DIFF_CALLBACK), ActionMode.Callback {

    private var multiSelect = false
    private val selectedArchives = mutableSetOf<Int>()
    private var actionMode: ActionMode? = null
    private val scope = fragment.lifecycleScope
    private val context = fragment.requireContext()
    private val fragmentManager = fragment.childFragmentManager
    private val listener = fragment.activity as? OnListFragmentInteractionListener
    private val listViewType = ListViewType.fromString(context, PreferenceManager.getDefaultSharedPreferences(context).getString(fragment.resources.getString(R.string.archive_list_type_key), ""))
    private val thumbLoadingJobs = mutableMapOf<ViewHolder, Job>()

    private val mOnClickListener: View.OnClickListener = View.OnClickListener { v ->
        val item = v.tag as ArchiveBase
        // Notify the active callbacks interface (the activity, if the fragment is attached to
        // one) that an item has been selected.
        listener?.onListFragmentInteraction(item, v)
    }

    private val onLongClickListener: View.OnLongClickListener = View.OnLongClickListener { v ->
        val item = v.tag as ArchiveBase
        longListener?.invoke(item) == true
    }

    init {
        viewModel.monitor(scope) { submitData(it) }
    }

    fun enableMultiSelect(activity: AppCompatActivity) : Boolean {
        multiSelect = true
        activity.startSupportActionMode(this)
        return true
    }

    fun disableMultiSelect() = actionMode?.finish()

    private fun selectArchive(position: Int) {
        if (!selectedArchives.remove(position))
            selectedArchives.add(position)

        actionMode?.title = context.getString(R.string.selected_archives, selectedArchives.size)
        notifyItemChanged(position)
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
            thumbLoadingJobs[holder] = scope.launch {
                val imageFile = DatabaseReader.getArchiveImage(it, holder.mView.context)
                imageFile?.let { file ->
                    holder.archiveImage.load(file) {
                        allowRgb565(true)
                        allowHardware(false)
                        crossfade(true)
                        if (listViewType == ListViewType.Cover)
                            transformations(StartCrop())
                    }
                }
            }

            if (listViewType == ListViewType.Card && holder.mContentView != null) {
                if (selectedArchives.contains(position))
                    holder.mContentView.setCardBackgroundColor(MaterialColors.getColor(holder.mContentView, R.attr.select_color))
                else
                    holder.mContentView.setCardBackgroundColor(MaterialColors.getColor(holder.mContentView, R.attr.cardBackgroundColor))
            } else if (listViewType == ListViewType.Cover) {
                if (selectedArchives.contains(position))
                    holder.archiveName.setBackgroundColor(MaterialColors.getColor(holder.archiveName, R.attr.select_color))
                else
                    holder.archiveName.setBackgroundColor(MaterialColors.getColor(holder.archiveName, R.attr.archive_color_label))
            }

            with(holder.mView) {
                tag = it
                setOnClickListener { view ->
                    if (!multiSelect)
                        mOnClickListener.onClick(view)
                    else
                        selectArchive(position)
                }
                setOnLongClickListener(onLongClickListener)
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        thumbLoadingJobs.remove(holder)?.cancel()
        with(holder.archiveImage) {
            dispose()
            setImageBitmap(null)
        }
        holder.archiveName.text = ""
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
        if (!ServerManager.canEdit) {
            menu?.run {
                findItem(R.id.delete_select_archive)?.isVisible = false
                findItem(R.id.category_select_item)?.isVisible = false
            }
        }
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
                        val ids = selectedArchives.mapNotNull { getItem(it)?.id }
                        scope.launch(Dispatchers.IO) {
                            val deleted = WebHandler.deleteArchives(ids)
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
                val archives = selectedArchives.mapNotNull { getItem(it) }
                scope.launch { ReaderTabHolder.addTabs(archives) }
                mode?.finish()
            }
            R.id.category_select_item -> {
                val dialog = AddToCategoryDialogFragment.newInstance(selectedArchives.mapNotNull { getItem(it)?.id })
                dialog.show(fragmentManager, "add_category")
            }
        }
        return true
    }

    fun onAddedToCategory(category: ArchiveCategory) {
        if (!multiSelect || selectedArchives.none())
            return

        Toast.makeText(context, context.getString(R.string.category_add_message, category.name), Toast.LENGTH_SHORT).show()
        actionMode?.finish()
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        multiSelect = false

        for (index in selectedArchives)
            notifyItemChanged(index)

        selectedArchives.clear()
        actionMode = null
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ArchiveBase>() {
            override fun areItemsTheSame(oldItem: ArchiveBase, newItem: ArchiveBase) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: ArchiveBase, newItem: ArchiveBase) = oldItem == newItem
        }
    }
}

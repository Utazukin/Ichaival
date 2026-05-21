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

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.NumberPicker
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import com.utazukin.ichaival.database.DatabaseReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val ARCHIVE_ID = "arcid"

class GalleryPreviewDialogFragment : DialogFragment(), ThumbRecyclerViewAdapter.ThumbInteractionListener, CoroutineScope {
    override val coroutineContext = lifecycleScope.coroutineContext
    private var archiveId: String = ""
    private var readerPage = -1
    private lateinit var listView: RecyclerView
    private lateinit var pagePicker: NumberPicker
    private var pickerState = NumberPicker.OnScrollListener.SCROLL_STATE_IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        val theme = when (context?.getCustomTheme()) {
            getString(R.string.material_theme) -> R.style.MaterialYou
            getString(R.string.dark_theme) -> R.style.AppTheme
            else -> R.style.AppTheme_Black
        }
        setStyle(STYLE_NORMAL, theme)
        super.onCreate(savedInstanceState)
        arguments?.run {
            archiveId = getString(ARCHIVE_ID, "")
            readerPage = getInt(FROM_READER_PAGE, -1)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_gallery_preview_dialog, container, false)
        listView = view.findViewById(R.id.thumb_list)
        pagePicker = view.findViewById(R.id.page_picker_preview)

        launch {
            DatabaseReader.getArchive(archiveId)?.let {
                with(pagePicker) {
                    minValue = 1
                    maxValue = it.numPages
                    value = readerPage + 1
                    setOnValueChangedListener { _, _, newValue ->
                        if (pickerState == NumberPicker.OnScrollListener.SCROLL_STATE_IDLE)
                            jumpToPage(newValue - 1)
                    }
                    setOnScrollListener { view, scrollState ->
                        if (scrollState == NumberPicker.OnScrollListener.SCROLL_STATE_IDLE)
                            jumpToPage(view.value - 1)
                        pickerState = scrollState
                    }
                }
                setGalleryView(view, it, savedInstanceState != null)
            }
        }

        return view
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_PORTRAIT -> {
                readerPage = pagePicker.value - 1
                parentFragmentManager.commit {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        setReorderingAllowed(false)
                    detach(this@GalleryPreviewDialogFragment)
                }
                parentFragmentManager.commit {
                    attach(this@GalleryPreviewDialogFragment)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
    }

    override fun onDetach() {
        super.onDetach()
        requireActivity().imageLoader.memoryCache?.clear()
    }

    override fun onThumbSelection(page: Int) {
        val listener = activity as? ThumbRecyclerViewAdapter.ThumbInteractionListener
        listener?.onThumbSelection(page)
        dismiss()
    }

    override fun onThumbLongPress(page: Int) = (activity as? ThumbRecyclerViewAdapter.ThumbInteractionListener)?.onThumbLongPress(page) == true

    private fun jumpToPage(page: Int) = listView.layoutManager?.scrollToPosition(page)

    private fun findFirstItem(layoutManager: RecyclerView.LayoutManager?) : Int {
        return when (layoutManager) {
            is LinearLayoutManager -> {
                val first = layoutManager.findFirstCompletelyVisibleItemPosition()
                if (first < 0)
                    layoutManager.findFirstVisibleItemPosition()
                else
                    first
            }
            else -> -1
        }
    }

    private fun RecyclerView.isPageVisible(page: Int) : Boolean {
        val viewHolder = findViewHolderForLayoutPosition(page - 1) ?: return false
        return layoutManager?.isViewPartiallyVisible(viewHolder.itemView, true, true) == true
    }

    private suspend fun setGalleryView(view: View, archive: Archive, restoring: Boolean) {
        val listView: RecyclerView = view.findViewById(R.id.thumb_list)
        with(listView) {
            val dpWidth = getDpWidth(requireActivity().getWindowWidth())
            val columns = dpWidth.floorDiv(150)
            val result = WebHandler.tryGenerateThumbs(archive.id)
            val thumbAdapter = ThumbRecyclerViewAdapter(this@GalleryPreviewDialogFragment, result, archive)
            thumbAdapter.useSubset(0)
            layoutManager = if (columns > 1) GridLayoutManager(context, columns) else LinearLayoutManager(context)

            adapter = thumbAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        val first = findFirstItem(recyclerView.layoutManager)
                        if (!recyclerView.isPageVisible(pagePicker.value) && first >= 0 && first != pagePicker.value)
                            pagePicker.value = first + 1
                    }
                }
            })

            if (!restoring && readerPage > 0)
                jumpToPage(readerPage)
        }
    }

    companion object {
        @JvmStatic
        fun createInstance(id: String, readerPage: Int) =
            GalleryPreviewDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARCHIVE_ID, id)
                    putInt(FROM_READER_PAGE, readerPage)
                }
            }
    }
}

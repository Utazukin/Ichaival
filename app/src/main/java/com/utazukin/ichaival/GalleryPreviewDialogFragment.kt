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

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

private const val ARCHIVE_ID = "arcid"
private const val MAX_PAGES = "max pages"

class GalleryPreviewDialogFragment : DialogFragment(), ThumbRecyclerViewAdapter.ThumbInteractionListener {
    private var archiveId: String? = null
    private var archive: Archive? = null
    private lateinit var thumbAdapter: ThumbRecyclerViewAdapter
    private lateinit var progress: ProgressBar
    private var savedPageCount = -1
    private var readerPage = -1
    private lateinit var listView: RecyclerView
    private lateinit var pagePicker: NumberPicker
    private var pickerState = NumberPicker.OnScrollListener.SCROLL_STATE_IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.run {
            archiveId = getString(ARCHIVE_ID)
            readerPage = getInt(FROM_READER_PAGE, -1)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_gallery_preview_dialog, container, false)
        progress = view.findViewById(R.id.thumb_load_progress)
        listView = view.findViewById(R.id.thumb_list)
        pagePicker = view.findViewById(R.id.page_picker_preview)


        lifecycleScope.launch {
            archive = withContext(Dispatchers.IO) { DatabaseReader.getArchive(archiveId!!) }
            pagePicker.run {
                minValue = 1
                maxValue = archive?.numPages ?: 1
                value = readerPage + 1
                setOnValueChangedListener { _, _, newValue ->
                    if (pickerState == NumberPicker.OnScrollListener.SCROLL_STATE_IDLE)
                        jumpToPage(newValue - 1, maxValue)
                }
                setOnScrollListener { view, scrollState ->
                    if (scrollState == NumberPicker.OnScrollListener.SCROLL_STATE_IDLE)
                        jumpToPage(view.value - 1, view.maxValue)
                    pickerState = scrollState
                }
            }
            setGalleryView(view)
        }

        return view
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
    }

    override fun onDetach() {
        super.onDetach()
        Glide.get(requireActivity()).clearMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(MAX_PAGES, thumbAdapter.maxThumbnails)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        savedInstanceState?.let {
            val maxPages = it.getInt(MAX_PAGES, -1)
            savedPageCount = maxPages
        }
    }

    override fun onThumbSelection(page: Int) {
        val listener = activity as? ThumbRecyclerViewAdapter.ThumbInteractionListener
        listener?.onThumbSelection(page)
        dismiss()
    }

    private fun jumpToPage(page: Int, max: Int) {
        if (page > thumbAdapter.maxThumbnails)
            thumbAdapter.maxThumbnails = min((ceil(page / 10f) * 10).toInt(), max)
        listView.layoutManager?.scrollToPosition(page)
    }

    private fun findFirstItem(layoutManager: RecyclerView.LayoutManager?) : Int {
        return if (layoutManager is LinearLayoutManager)
            layoutManager.findFirstCompletelyVisibleItemPosition()
        else if (layoutManager is GridLayoutManager)
            layoutManager.findFirstCompletelyVisibleItemPosition()
        else -1
    }

    private fun RecyclerView.isPageVisible(page: Int) : Boolean {
        val viewHolder = this.findViewHolderForLayoutPosition(page - 1) ?: return false
        return this.layoutManager?.isViewPartiallyVisible(viewHolder.itemView, true, true) ?: false
    }

    private fun setGalleryView(view: View) {
        val listView: RecyclerView = view.findViewById(R.id.thumb_list)
        with(listView) {
            post {
                val dpWidth = getDpWidth(width)
                val columns = floor(dpWidth / 150.0).toInt()
                layoutManager = if (columns > 1) GridLayoutManager(
                    context,
                    columns
                ) else LinearLayoutManager(context)

                if (savedPageCount <= 0) {
                    archive?.let {
                        if (readerPage > 0)
                            jumpToPage(readerPage, it.numPages)
                    }
                }
            }
            thumbAdapter = ThumbRecyclerViewAdapter(this@GalleryPreviewDialogFragment, Glide.with(requireActivity()), lifecycleScope, archive!!)
            if (savedPageCount > 0)
                thumbAdapter.maxThumbnails = savedPageCount
            adapter = thumbAdapter
            addOnScrollListener(object: RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    if (!listView.canScrollVertically(1) && thumbAdapter.hasMorePreviews) {
                        thumbAdapter.increasePreviewCount()
                        lifecycleScope.launch {
                            progress.visibility = View.VISIBLE
                            delay(1000)
                            progress.visibility = View.GONE
                        }
                    }

                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        val first = findFirstItem(recyclerView.layoutManager)
                        if (!recyclerView.isPageVisible(pagePicker.value) && first >= 0 && first != pagePicker.value)
                            pagePicker.value = first + 1
                    }
                }
            })
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

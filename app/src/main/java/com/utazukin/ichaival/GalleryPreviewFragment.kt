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


import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import com.utazukin.ichaival.database.DatabaseReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val ARCHIVE_ID = "arcid"
private const val CHAPTER_PAGE = "chapter page"

class GalleryPreviewFragment : Fragment(), CoroutineScope, MenuProvider {
    override val coroutineContext = lifecycleScope.coroutineContext
    private var archiveId: String? = null
    private var archive: Archive? = null
    private lateinit var thumbAdapter: ThumbRecyclerViewAdapter
    private lateinit var progress: ProgressBar
    private var readerPage = -1
    private lateinit var listView: RecyclerView
    private lateinit var tocButton: Button
    private var currentToc: List<ToCEntry>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.run {
            archiveId = getString(ARCHIVE_ID)
            readerPage = getInt(FROM_READER_PAGE, -1)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_gallery_preview, container, false)
        progress = view.findViewById(R.id.thumb_load_progress)
        listView = view.findViewById(R.id.thumb_list)
        tocButton = view.findViewById(R.id.btn_toc)

        with(requireActivity() as MenuHost) {
            addMenuProvider(this@GalleryPreviewFragment, viewLifecycleOwner, Lifecycle.State.RESUMED)
        }

        launch {
            archive = DatabaseReader.getArchive(archiveId!!)
            setGalleryView(savedInstanceState)
            DatabaseReader.getToC(archiveId!!).collectLatest {
                var firstThumb = -1
                if (it.isNotEmpty() && currentToc != null) {
                    for ((i, chapter) in it.withIndex()) {
                        if (chapter.page != currentToc?.getOrNull(i)?.page) {
                            firstThumb = chapter.page
                            break
                        }
                    }
                }

                currentToc = it
                updateToCButton(it, savedInstanceState, firstThumb)
            }
        }

        return view
    }

    private fun updateToCButton(toc: List<ToCEntry>, savedInstanceState: Bundle? = null, firstThumb: Int = -1) {
        if (toc.isNotEmpty()) {
            with(tocButton) {
                val items = if (toc[0].page > 0) {
                    buildList(toc.size + 1) {
                        add(ToCEntry(resources.getString(R.string.default_chapter), 0))
                        addAll(toc)
                    }
                } else toc

                val currentIndex = when {
                    savedInstanceState != null -> items.indexOfFirst { it.page == savedInstanceState.getInt(CHAPTER_PAGE) }
                    firstThumb >= 0 -> items.indexOfFirst { it.page == firstThumb }
                    else -> items.indexOfFirst { it.page == thumbAdapter.firstThumb }
                }

                if (currentIndex >= 0) {
                    val currentItem = items[currentIndex]
                    text = currentItem.name
                    val start = items[currentIndex].page
                    val end = if (currentIndex < items.size - 1) items[currentIndex + 1].page else archive!!.numPages
                    thumbAdapter.useSubset(start, end)
                }

                setOnClickListener {
                    launch {
                        val dialog = AlertDialog.Builder(requireContext()).apply {
                            val current = items.indexOfFirst { it.page == thumbAdapter.firstThumb }
                            setSingleChoiceItems(items.map { it.name }.toTypedArray(), current) { dialog, id ->
                                val start = items[id].page
                                val end = if (id < items.size - 1) items[id + 1].page else archive!!.numPages
                                thumbAdapter.useSubset(start, end)
                                tocButton.text = items[id].name
                                dialog.dismiss()
                            }
                        }.create()
                        dialog.show()
                    }
                }
                visibility = View.VISIBLE
            }
        } else {
            tocButton.visibility = View.GONE
            if (thumbAdapter.firstThumb > 0)
                thumbAdapter.useSubset(0)
        }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.gallery_preview_menu, menu)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.refresh_item -> {
                archiveId?.let {
                    DatabaseReader.invalidateImageCache(it)
                    launch {
                        (activity as ArchiveDetails).extractArchive(it)
                        thumbAdapter.notifyDataSetChanged()
                    }
                }
            }
        }
        return false
    }

    override fun onDetach() {
        super.onDetach()
        requireActivity().imageLoader.memoryCache?.clear()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(CHAPTER_PAGE, thumbAdapter.firstThumb)
    }

    private fun setGalleryView(savedInstanceBundle: Bundle?) {
        with(listView) {
            val dpWidth = getDpWidth(requireActivity().getWindowWidth())
            val columns = dpWidth.floorDiv(150)
            thumbAdapter = ThumbRecyclerViewAdapter(this@GalleryPreviewFragment, archive!!)
            layoutManager = (if (columns > 1) GridLayoutManager(context, columns) else LinearLayoutManager(context)).apply {
                if (savedInstanceBundle != null) {
                    archive?.let {
                        val page = if (readerPage > -1) readerPage else it.currentPage
                        if (page > 0)
                            scrollToPosition(page)
                    }
                }
            }

            adapter = thumbAdapter
        }
    }

    companion object {
        @JvmStatic
        fun createInstance(id: String, readerPage: Int) =
            GalleryPreviewFragment().apply {
                arguments = Bundle().apply {
                    putString(ARCHIVE_ID, id)
                    putInt(FROM_READER_PAGE, readerPage)
                }
            }
    }
}

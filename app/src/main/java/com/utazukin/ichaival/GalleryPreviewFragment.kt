/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2023 Utazukin
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
import android.widget.ProgressBar
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import com.utazukin.ichaival.database.DatabaseReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

private const val ARCHIVE_ID = "arcid"
private const val MAX_PAGES = "max pages"

class GalleryPreviewFragment : Fragment() {
    private var archiveId: String? = null
    private var archive: Archive? = null
    private lateinit var thumbAdapter: ThumbRecyclerViewAdapter
    private lateinit var progress: ProgressBar
    private var savedPageCount = -1
    private var readerPage = -1
    private lateinit var listView: RecyclerView

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

        with(requireActivity() as MenuHost) {
            addMenuProvider(object: MenuProvider {
                override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
                    inflater.inflate(R.menu.gallery_preview_menu, menu)
                }

                override fun onMenuItemSelected(item: MenuItem): Boolean {
                    when (item.itemId) {
                        R.id.refresh_item -> {
                            archiveId?.let {
                                DatabaseReader.invalidateImageCache(it)
                                lifecycleScope.launch {
                                    (activity as ArchiveDetails).extractArchive(it)
                                    thumbAdapter.notifyDataSetChanged()
                                }
                            }
                        }
                    }
                    return false
                }
            }, viewLifecycleOwner, Lifecycle.State.RESUMED)
        }

        lifecycleScope.launch {
            archive = DatabaseReader.getArchive(archiveId!!)
            setGalleryView(view)
        }

        return view
    }

    override fun onDetach() {
        super.onDetach()
        requireActivity().imageLoader.memoryCache?.clear()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(MAX_PAGES, thumbAdapter.maxThumbnails)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        savedInstanceState?.let {
            val maxPages = it.getInt(MAX_PAGES, -1)
            savedPageCount = maxPages
        }
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
                        val page = if (readerPage > -1) readerPage else it.currentPage
                        if (page > 0) {
                            thumbAdapter.maxThumbnails =
                                min((ceil(page / 10f) * 10).toInt(), it.numPages)
                            layoutManager?.scrollToPosition(page)
                        }
                    }
                }
            }
            val loader = if (!ServerManager.canEdit && !ServerManager.checkVersionAtLeast(0, 8, 5))
                context.imageLoader
            else
                context.imageLoader.newBuilder().okHttpClient { WebHandler.httpClient.newBuilder().addInterceptor(ThumbHttpInterceptor()).build() }.build()
            thumbAdapter = ThumbRecyclerViewAdapter(this@GalleryPreviewFragment, archive!!, loader)
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
                }
            })
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

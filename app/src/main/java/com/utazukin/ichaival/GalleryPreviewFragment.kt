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


import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.utazukin.ichaival.ThumbRecyclerViewAdapter.ThumbInteractionListener
import kotlinx.coroutines.*


private const val ARCHIVE_ID = "arcid"
private const val MAX_PAGES = "max pages"

class GalleryPreviewFragment : Fragment(), ThumbInteractionListener {
    private var archiveId: String? = null
    private var archive: Archive? = null
    private lateinit var thumbAdapter: ThumbRecyclerViewAdapter
    private lateinit var activityScope: CoroutineScope
    private lateinit var progress: ProgressBar
    private var savedPageCount = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            archiveId = it.getString(ARCHIVE_ID)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_gallery_preview, container, false)
        progress = view.findViewById(R.id.thumb_load_progress)

        activityScope.launch {
            archive = withContext(Dispatchers.Default) { DatabaseReader.getArchive(archiveId!!, context!!.filesDir) }
            setGalleryView(view)
        }

        return view
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        activityScope = context as CoroutineScope
    }

    override fun onDetach() {
        super.onDetach()
        Glide.get(activity!!).clearMemory()
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

    private fun setGalleryView(view: View) {
        val listener: ThumbInteractionListener = this
        val listView: RecyclerView = view.findViewById(R.id.thumb_list)
        with(listView) {
            post {
                val dpWidth = getDpWidth(width)
                val columns = Math.floor(dpWidth / 150.0).toInt()
                layoutManager = if (columns > 1) GridLayoutManager(
                    context,
                    columns
                ) else LinearLayoutManager(context)
            }
            thumbAdapter = ThumbRecyclerViewAdapter(listener, Glide.with(activity!!), activityScope, archive!!)
            if (savedPageCount > 0)
                thumbAdapter.maxThumbnails = savedPageCount
            adapter = thumbAdapter
            addOnScrollListener(object: RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    if (!listView.canScrollVertically(1) && thumbAdapter.hasMorePreviews) {
                        thumbAdapter.increasePreviewCount()
                        activityScope.launch {
                            progress.visibility = View.VISIBLE
                            delay(1000)
                            progress.visibility = View.GONE
                        }
                    }
                }
            })
        }
    }

    override fun onThumbSelection(page: Int) {
        val intent = Intent(activity, ReaderActivity::class.java)
        val bundle = Bundle()
        bundle.putString("id", archiveId)
        bundle.putInt("page", page)
        intent.putExtras(bundle)
        startActivity(intent)
    }

    companion object {
        @JvmStatic
        fun createInstance(id: String) =
            GalleryPreviewFragment().apply {
                arguments = Bundle().apply {
                    putString(ARCHIVE_ID, id)
                }
            }
    }
}

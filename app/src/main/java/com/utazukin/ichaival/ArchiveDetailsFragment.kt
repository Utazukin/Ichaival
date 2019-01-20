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
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import kotlinx.coroutines.*


private const val ARCHIVE_ID = "arcid"

class ArchiveDetailsFragment : Fragment(), TabRemovedListener {
    private var archiveId: String? = null
    private var archive: Archive? = null
    private lateinit var tagLayout: LinearLayout
    private lateinit var bookmarkButton: Button
    private var thumbLoadJob: Job? = null
    private lateinit var scope: CoroutineScope
    private var tagListener: TagInteractionListener? = null

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
        val view = inflater.inflate(R.layout.fragment_archive_details, container, false)
        tagLayout = view.findViewById(R.id.tag_layout)

        scope.launch {
            archive = withContext(Dispatchers.Default) { DatabaseReader.getArchive(archiveId!!, context!!.filesDir) }
            setUpDetailView(view)
        }
        return view
    }

    override fun onAttach(context: Context?) {
        super.onAttach(context)
        ReaderTabHolder.registerRemoveListener(this)
        scope = context as CoroutineScope
        tagListener = context as TagInteractionListener?
    }

    override fun onDetach() {
        super.onDetach()
        ReaderTabHolder.unregisterRemoveListener(this)
    }

    override fun onTabRemoved(id: String) {
        if (id == archiveId)
            bookmarkButton.text = getString(R.string.bookmark)
    }

    override fun onDestroy() {
        thumbLoadJob?.cancel()
        super.onDestroy()
    }

    private fun setUpTags() {
        val archiveCopy = archive ?: return

        for (pair in archiveCopy.tags) {
            if (pair.value.isEmpty())
                continue

            val namespace = if (pair.key == "global") "Other:" else "${pair.key}:"
            val namespaceLayout = FlexboxLayout(context)
            namespaceLayout.flexWrap = FlexWrap.WRAP
            namespaceLayout.flexDirection = FlexDirection.ROW
            tagLayout.addView(namespaceLayout, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val namespaceView = createTagView(namespace)
            namespaceLayout.addView(namespaceView)

            for (tag in pair.value) {
                val tagView = createTagView(tag)
                namespaceLayout.addView(tagView)
                tagView.setOnClickListener { tagListener?.onTagInteraction(tag) }
            }
        }
    }

    private fun createTagView(tag: String) : TextView {
        val tagView = TextView(context)
        tagView.text = tag
        tagView.background = ContextCompat.getDrawable(context!!, R.drawable.tag_background)
        tagView.setTextColor(Color.WHITE)
        val layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        layoutParams.setMargins(10, 10, 10, 10)
        tagView.layoutParams = layoutParams
        return tagView
    }

    private fun setUpDetailView(view: View) {
        bookmarkButton = view.findViewById(R.id.bookmark_button)
        with(bookmarkButton) {
            setOnClickListener {
                val copy = archive
                if (copy != null) {
                    if (ReaderTabHolder.isTabbed(copy.id)) {
                        ReaderTabHolder.removeTab(copy.id)
                        text = getString(R.string.bookmark)
                    } else {
                        ReaderTabHolder.addTab(copy, 0)
                        text = getString(R.string.unbookmark)
                    }
                }
            }
            text = getString(if (ReaderTabHolder.isTabbed(archive?.id)) R.string.unbookmark else R.string.bookmark)
        }

        setUpTags()

        val readButton: Button = view.findViewById(R.id.read_button)
        readButton.setOnClickListener {
            val intent = Intent(activity, ReaderActivity::class.java)
            val bundle = Bundle()
            bundle.putString("id", archiveId)
            intent.putExtras(bundle)
            startActivity(intent)
        }

        val titleView: TextView = view.findViewById(R.id.title)
        titleView.text = archive?.title

        thumbLoadJob = scope.launch(Dispatchers.Main) {
            val thumbView: ImageView = view.findViewById(R.id.cover)
            val thumb = withContext(Dispatchers.Default) { DatabaseReader.getArchiveImage(archive!!, context!!.filesDir) }
            val request = Glide.with(thumbView).asBitmap().load(thumb)
            request.into(thumbView)

            //Replace the thumbnail with the full size image.
            val image = withContext(Dispatchers.Default) { archive?.getPageImage(0) }
            Glide.with(thumbView).asBitmap().load(image).thumbnail(request).into(thumbView)
        }
    }

    interface TagInteractionListener {
        fun onTagInteraction(tag: String)
    }

    companion object {
        @JvmStatic
        fun createInstance(id: String) =
            ArchiveDetailsFragment().apply {
                arguments = Bundle().apply {
                    putString(ARCHIVE_ID, id)
                }
            }
    }
}

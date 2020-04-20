/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2020 Utazukin
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


import android.graphics.Color
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ARCHIVE_PARAM = "archive"

typealias TagPressListener = (String) -> Unit
typealias TagLongPressListener = (String) -> Boolean

class TagDialogFragment : DialogFragment() {
    private lateinit var tagLayout: LinearLayout
    private var tagPressListener: TagPressListener? = null
    private var tagLongPressListener: TagLongPressListener? = null
    private val isLocalSearch by lazy {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.getBoolean(getString(R.string.local_search_key), false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            val archiveId = it.getString(ARCHIVE_PARAM)
            archiveId?.let {
                lifecycleScope.launch {
                    val archive = withContext(Dispatchers.IO) { DatabaseReader.getArchive(archiveId) }
                    if (archive != null)
                        setUpTags(archive)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.tag_popup_layout, container, false)
        tagLayout = view.findViewById(R.id.tag_layout)
        return view
    }

    private fun getSearchTag(tag: String, namespace: String) : String {
        return when {
            namespace == "Other:" -> "\"$tag\""
            isLocalSearch -> "$namespace\"$tag\""
            else -> "\"$namespace$tag\"$"
        }
    }

    private fun setUpTags(archive: Archive) {
        for (pair in archive.tags) {
            if (pair.value.isEmpty())
                continue

            val namespace = if (pair.key == "global") "Other:" else "${pair.key}:"
            val namespaceLayout = FlexboxLayout(context)
            namespaceLayout.flexWrap = FlexWrap.WRAP
            namespaceLayout.flexDirection = FlexDirection.ROW
            tagLayout.addView(
                namespaceLayout,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val namespaceView = createTagView(namespace)
            namespaceLayout.addView(namespaceView)

            val isSource = namespace == "source:"
            for (tag in pair.value) {
                val tagView = createTagView(tag)
                if (!isSource) {
                    val searchTag = getSearchTag(tag, namespace)
                    tagView.setOnClickListener { tagPressListener?.invoke(searchTag); dismiss() }
                    tagView.setOnLongClickListener {
                        val response = tagLongPressListener?.invoke(searchTag)
                        dismiss()
                        response == true
                    }
                } else {
                    Linkify.addLinks(tagView, Linkify.WEB_URLS)
                    tagView.linksClickable = true
                    tagView.movementMethod = LinkMovementMethod.getInstance()
                }
                namespaceLayout.addView(tagView)
            }
        }
    }

    private fun createTagView(tag: String) : TextView {
        val tagView = TextView(context)
        tagView.text = tag
        tagView.background = ContextCompat.getDrawable(requireContext(), R.drawable.tag_background)
        tagView.setTextColor(Color.WHITE)
        val layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        layoutParams.setMargins(10, 10, 10, 10)
        tagView.layoutParams = layoutParams

        return tagView
    }

    fun setTagPressListener(listener: TagPressListener?) {
        tagPressListener = listener
    }

    fun setTagLongPressListener(listener: TagLongPressListener?) {
        tagLongPressListener = listener
    }

    companion object {
        @JvmStatic
        fun newInstance(archiveId: String) =
            TagDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARCHIVE_PARAM, archiveId)
                }
            }
    }
}

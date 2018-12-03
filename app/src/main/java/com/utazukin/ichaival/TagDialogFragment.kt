/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2018 Utazukin
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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

private const val ARCHIVE_PARAM = "archive"

class TagDialogFragment : DialogFragment() {
    private var archive: Archive? = null
    private lateinit var tagLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            val archiveId = it.getString(ARCHIVE_PARAM)
            archiveId?.let {
                GlobalScope.launch(Dispatchers.Main) {
                    archive = async { DatabaseReader.getArchive(archiveId, activity!!.filesDir) }.await()
                    setUpTags()
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

    private fun setUpTags() {
        archive?.run {
            for (pair in tags) {
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

                for (tag in pair.value) {
                    val tagView = createTagView(tag)
                    namespaceLayout.addView(tagView)
                }
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

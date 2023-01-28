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


import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

private const val ARCHIVE_PARAM = "archive"

typealias TagPressListener = (String) -> Unit
typealias TagLongPressListener = (String) -> Boolean

class TagDialogFragment : DialogFragment() {
    private lateinit var tagLayout: LinearLayout
    private var tagPressListener: TagPressListener? = null
    private var tagLongPressListener: TagLongPressListener? = null
    private val isLocalSearch by lazy {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        prefs.getBoolean(getString(R.string.local_search_key), false)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return when (context?.getCustomTheme()) {
            getString(R.string.material_theme) -> MaterialAlertDialogBuilder(requireContext(), theme)
            else -> AlertDialog.Builder(requireContext(), theme)
        }.apply { setView(setupDialog()) }.create()
    }

    private fun setupDialog() : View? {
        return layoutInflater.inflate(R.layout.tag_popup_layout, null, false)?.apply {
            tagLayout = findViewById(R.id.tag_layout)
            arguments?.run {
                getString(ARCHIVE_PARAM)?.let {
                    lifecycleScope.launch {
                        val archive = DatabaseReader.getArchive(it)
                        if (archive != null)
                            setUpTags(archive)
                    }
                }
            }
        }
    }

    private fun getSearchTag(tag: String, namespace: String) : String {
        return when {
            namespace == getString(R.string.other_namespace) -> "\"$tag\""
            isLocalSearch -> "$namespace:\"$tag\""
            else -> "\"$namespace:$tag\"$"
        }
    }

    private fun setUpTags(archive: Archive) {
        for ((namespace, tags) in archive.tags) {
            if (tags.isEmpty())
                continue

            val namespace = if (namespace == "global") getString(R.string.other_namespace) else namespace
            val namespaceLayout = FlexboxLayout(context)
            namespaceLayout.flexWrap = FlexWrap.WRAP
            namespaceLayout.flexDirection = FlexDirection.ROW
            tagLayout.addView(
                namespaceLayout,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val namespaceView = createTagView(namespace, true)
            namespaceLayout.addView(namespaceView)

            val isSource = namespace == "source"
            for (tag in tags) {
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

    private fun createTagView(tag: String, isNamespace: Boolean = false) : TextView {
        return TextView(context).apply {
            text = tag
            background = if (isNamespace)
                    ContextCompat.getDrawable(requireContext(), R.drawable.namespace_background)
                else
                    ContextCompat.getDrawable(requireContext(), R.drawable.tag_background)
            setTextColor(Color.WHITE)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(10, 10, 10, 10)
            layoutParams = params
        }
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

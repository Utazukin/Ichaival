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

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.utazukin.ichaival.database.DatabaseReader
import kotlinx.coroutines.launch

interface ChapterEditListener {
    fun onChapterEdit(name: String, page: Int, delete: Boolean)
}

class EditChapterDialogFragment : DialogFragment() {
    private var listener: ChapterEditListener? = null
    private var pageNum = 0
    private var archiveId = ""
    private lateinit var edtChapter: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        with(requireArguments()) {
            pageNum = getInt(PAGE_KEY)
            archiveId = getString(ID_KEY)!!
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return when (context?.getCustomTheme()) {
            getString(R.string.material_theme) -> MaterialAlertDialogBuilder(requireContext(), theme)
            else -> AlertDialog.Builder(requireContext(), theme)
        }.setView(setupDialog()).create()
    }

    private fun setupDialog(): View? {
        val view = layoutInflater.inflate(R.layout.fragment_add_chapter_dialog, null, false)

        val okButton: Button = view.findViewById(R.id.btn_accept)
        val cancelButton: Button = view.findViewById(R.id.btn_cancel)
        val deleteButton: Button = view.findViewById(R.id.btn_delete_chapter)
        edtChapter = view.findViewById(R.id.edt_chapter_name)

        cancelButton.setOnClickListener { dismiss() }
        okButton.setOnClickListener {
            if (edtChapter.text.isNotBlank()) {
                listener?.onChapterEdit(edtChapter.text.toString(), pageNum, false)
                dismiss()
            }
        }

        deleteButton.setOnClickListener {
            listener?.onChapterEdit("", pageNum, true)
            dismiss()
        }

        lifecycleScope.launch {
            val entry = DatabaseReader.getToCEntry(pageNum, archiveId)
            if (entry != null) {
                edtChapter.hint = entry.name
                deleteButton.visibility = View.VISIBLE
                deleteButton.isEnabled = true
            } else {
                deleteButton.isEnabled = false
                deleteButton.visibility = View.GONE
            }
        }

        return view
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as? ChapterEditListener ?: context as? ChapterEditListener
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    companion object {
        private const val PAGE_KEY = "page"
        private const val ID_KEY = "id"

        @JvmStatic
        fun newInstance(page: Int, archiveId: String) = EditChapterDialogFragment().apply {
            arguments = Bundle().apply {
                putInt(PAGE_KEY, page)
                putString(ID_KEY, archiveId)
            }
        }
    }
}
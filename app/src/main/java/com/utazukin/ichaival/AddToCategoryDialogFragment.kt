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
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

private const val ARCHIVE_PARAM = "archive"

interface AddCategoryListener {
    fun onAddedToCategory(category: ArchiveCategory, archiveIds: List<String>)
}

class AddToCategoryDialogFragment : DialogFragment(), CategoryListener {
    private var listener: AddCategoryListener? = null
    private val archiveIds = mutableListOf<String>()
    private var categories: List<ArchiveCategory>? = null
    private lateinit var catGroup: RadioGroup
    private lateinit var catText: EditText
    private lateinit var newCatButton: Button
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        archiveIds.clear()
        arguments?.let {
            val ids = it.getStringArrayList(ARCHIVE_PARAM)
            if (ids != null)
                archiveIds.addAll(ids)
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as? AddCategoryListener
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        CategoryManager.addUpdateListener(this)
    }

    override fun onStop() {
        super.onStop()
        CategoryManager.removeUpdateListener(this)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return when (context?.getCustomTheme()) {
            getString(R.string.material_theme) -> MaterialAlertDialogBuilder(requireContext(), theme)
            else -> AlertDialog.Builder(requireContext(), theme)
        }.apply { setView(setupDialog()) }.create()
    }

    private fun setupDialog() : View {
        val view = layoutInflater.inflate(R.layout.fragment_add_to_category_dialog, null, false)

        catGroup = view.findViewById(R.id.cat_rad_group)
        catText = view.findViewById(R.id.new_cat_txt)
        newCatButton = view.findViewById(R.id.new_cat_radio)
        scrollView = view.findViewById(R.id.category_scroll)
        val addButton: Button = view.findViewById(R.id.add_to_cat_dialog_button)

        catGroup.check(R.id.new_cat_radio)
        catGroup.setOnCheckedChangeListener { _, i ->
            when (i) {
                R.id.new_cat_radio -> {
                    catText.visibility = View.VISIBLE
                    with(scrollView) { post { fullScroll(View.FOCUS_DOWN) } }
                }
                else -> catText.visibility = View.GONE
            }
        }

        addButton.setOnClickListener {
            if (catGroup.checkedRadioButtonId == R.id.new_cat_radio) {
                if (catText.text.isNotBlank()) {
                    lifecycleScope.launch {
                        val name = catText.text.toString()
                        val category = CategoryManager.createCategory(requireContext(), name)
                        category?.let {
                            val success = WebHandler.addToCategory(requireContext(), it.id, archiveIds)
                            if (success) {
                                ServerManager.parseCategories(requireContext())
                                listener?.onAddedToCategory(it, archiveIds)
                            }
                        }
                        dismiss()
                    }
                } else Toast.makeText(requireContext(), "Category name cannot be empty.", Toast.LENGTH_SHORT).show()
            } else {
                lifecycleScope.launch {
                    val category = categories?.get(catGroup.checkedRadioButtonId)
                    category?.let {
                        val success = WebHandler.addToCategory(requireContext(), it.id, archiveIds)
                        if (success) {
                            ServerManager.parseCategories(requireContext())
                            listener?.onAddedToCategory(it, archiveIds)
                        }
                    }
                    dismiss()
                }
            }
        }

        return view
    }

    override fun onCategoriesUpdated(categories: List<ArchiveCategory>?) {
        this.categories = categories
        categories?.let {
            catGroup.removeAllViews()
            for ((i, category) in it.withIndex()) {
                if (category.isStatic) {
                    val categoryButton = AppCompatRadioButton(context).apply {
                        text = category.name
                        id = i
                    }
                    catGroup.addView(categoryButton, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            }
            catGroup.addView(newCatButton)
            catGroup.addView(catText)
        }

    }

    companion object {
        @JvmStatic
        fun newInstance(ids: List<String>) =
            AddToCategoryDialogFragment().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARCHIVE_PARAM, ArrayList(ids))
                }
            }
    }
}
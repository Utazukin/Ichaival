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

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.utazukin.ichaival.database.DatabaseReader
import com.utazukin.ichaival.database.SearchViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class SortMethod(val value: Int) {
    Alpha(1),
    Date(2);

    companion object {
        private val map by lazy { entries.associateBy(SortMethod::value)}
        fun fromInt(type: Int, default: SortMethod = Alpha) = map[type] ?: default
    }
}

enum class StatusFilter(val value: Int) {
    None(0),
    OnlyNew(1),
    InProgress(2),
    Completed(3);
}

class CategoryFilterFragment : Fragment() {
    private lateinit var categoryGroup: ChipGroup
    private var currentCategories: List<ArchiveCategory>? = null
    private var categoryLabel: TextView? = null
    private var listener: FilterListener? = null
    private val viewModel: SearchViewModel by activityViewModels()
    private val categoryButtons = mutableListOf<Chip>()
    private var savedCategory: ArchiveCategory? = null
    val selectedCategory: ArchiveCategory?
        get() {
            return when {
                currentCategories == null -> savedCategory
                categoryGroup.checkedChipId >= 0 -> currentCategories?.get(categoryGroup.checkedChipId)
                else -> null
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_category_filter, container, false)
        categoryGroup = view.findViewById(R.id.category_button_group)
        categoryLabel = view.findViewById(R.id.category_label)

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        lifecycleScope.launch {
            with(view) {
                val dirGroup: RadioGroup = findViewById(R.id.direction_group)
                val sortGroup: RadioGroup = findViewById(R.id.sort_group)
                when (viewModel.sortMethod) {
                    SortMethod.Alpha -> sortGroup.check(R.id.rad_alpha)
                    SortMethod.Date -> sortGroup.check(R.id.rad_date)
                }

                dirGroup.check(if (viewModel.descending) R.id.rad_desc else R.id.rad_asc)

                sortGroup.setOnCheckedChangeListener { _, id ->
                    val sortMethod = getMethodFromId(id)
                    prefs.edit { putInt(getString(R.string.sort_pref), sortMethod.value) }
                    viewModel.sortMethod = sortMethod
                }

                dirGroup.setOnCheckedChangeListener { _, id ->
                    val descending = getDirectionFromId(id)
                    prefs.edit { putBoolean(getString(R.string.desc_pref), descending) }
                    viewModel.descending = descending
                }

                with(findViewById<ChipGroup>(R.id.status_filter_group)) {
                    when (viewModel.status) {
                        StatusFilter.OnlyNew -> check(R.id.chip_new)
                        StatusFilter.InProgress -> check(R.id.chip_progress)
                        StatusFilter.Completed -> check(R.id.chip_complete)
                        else -> {}
                    }

                    setOnCheckedStateChangeListener { _, ids ->
                        viewModel.status = when (ids.firstOrNull()) {
                            R.id.chip_new -> StatusFilter.OnlyNew
                            R.id.chip_progress -> StatusFilter.InProgress
                            R.id.chip_complete -> StatusFilter.Completed
                            else -> StatusFilter.None
                        }
                    }
                }
            }

            DatabaseReader.getAllCategories().collectLatest {
                onCategoriesUpdated(it)
            }
        }

        return view
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedCategory?.let { outState.putParcelable("cat", it) }
    }

    @Suppress("DEPRECATION")
    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        savedInstanceState?.run {
            savedCategory = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> getParcelable("cat")
                else -> getParcelable("cat", ArchiveCategory::class.java)
            }
        }
    }

    private fun onCategoriesUpdated(categories: List<ArchiveCategory>) {
        currentCategories = categories

        val label = categoryLabel ?: return
        clearCategory()
        categoryButtons.clear()
        categoryGroup.removeAllViews()
        if (categories.isNotEmpty()) {
            label.visibility = View.VISIBLE
            categoryGroup.visibility = View.VISIBLE
            for ((i, category) in categories.withIndex()) {
                val categoryButton = Chip(context).apply {
                    text = category.name
                    id = i
                    isCheckable = true
                    setOnClickListener { listener?.onCategoryChanged(selectedCategory) }
                }
                categoryGroup.addView(categoryButton, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                categoryButtons.add(categoryButton)
            }

            savedCategory?.run {
                categoryButtons.firstOrNull { it.text == name }?.isChecked = true
                savedCategory = null
            }
        } else {
            label.visibility = View.GONE
            categoryGroup.visibility = View.GONE
        }
    }

    fun clearCategory() = categoryGroup.clearCheck()

    private fun getMethodFromId(id: Int) : SortMethod {
        return when(id) {
            R.id.rad_alpha -> SortMethod.Alpha
            R.id.rad_date -> SortMethod.Date
            else -> SortMethod.Alpha
        }
    }

    private fun getDirectionFromId(id: Int) : Boolean {
        return when(id) {
            R.id.rad_asc -> false
            R.id.rad_desc -> true
            else -> false
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? FilterListener
    }
}

interface FilterListener {
    fun onCategoryChanged(category: ArchiveCategory?)
}

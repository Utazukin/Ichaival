/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2021 Utazukin
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
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager

enum class SortMethod(val value: Int) {
    Alpha(1),
    Date(2);

    companion object {
        private val map by lazy { values().associateBy(SortMethod::value)}
        fun fromInt(type: Int) = map[type]
    }
}

class CategoryFilterFragment : Fragment(), CategoryListener {
    private lateinit var categoryGroup: RadioGroup
    private var currentCategories: List<ArchiveCategory>? = null
    private lateinit var categoryLabel: TextView
    private var listener: FilterListener? = null
    private var sortMethod = SortMethod.Alpha
    private var descending = false
    private val categoryButtons = mutableListOf<AppCompatRadioButton>()
    val selectedCategory: ArchiveCategory?
        get() {
            if (categoryGroup.checkedRadioButtonId >= 0)
                return currentCategories?.get(categoryGroup.checkedRadioButtonId)
            return null
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_category_filter, container, false)
        categoryGroup = view.findViewById(R.id.category_button_group)
        categoryLabel = view.findViewById(R.id.category_label)

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        sortMethod = SortMethod.fromInt(prefs.getInt(getString(R.string.sort_pref), 1)) ?: SortMethod.Alpha
        descending = prefs.getBoolean(getString(R.string.desc_pref), false)

        view.run {
            val sortGroup: RadioGroup = findViewById(R.id.sort_group)
            sortGroup.setOnCheckedChangeListener { _, id ->
                sortMethod = getMethodFromId(id)
                listener?.onSortChanged(sortMethod, descending)
            }

            val dirGroup: RadioGroup = findViewById(R.id.direction_group)
            dirGroup.setOnCheckedChangeListener { _, id ->
                descending = getDirectionFromId(id)
                listener?.onSortChanged(sortMethod, descending)
            }

            when (sortMethod) {
                SortMethod.Alpha -> sortGroup.check(R.id.rad_alpha)
                SortMethod.Date -> sortGroup.check(R.id.rad_date)
            }

            dirGroup.check(if (descending) R.id.rad_desc else R.id.rad_asc)
        }

        return view
    }

    override fun onCategoriesUpdated(categories: List<ArchiveCategory>?) {
        currentCategories = categories

        clearCategory()
        categoryButtons.clear()
        if (!categories.isNullOrEmpty()) {
            categoryLabel.visibility = View.VISIBLE
            for ((i, category) in categories.withIndex()) {
                val categoryButton = AppCompatRadioButton(context)
                categoryButton.text = category.name
                categoryButton.id = i
                categoryGroup.addView(categoryButton)
                categoryButtons.add(categoryButton)
            }

            categoryGroup.setOnCheckedChangeListener { _, i ->
                if (i >= 0 && categoryButtons[i].isChecked) //Check if really checked due to a bug in clearCheck().
                    listener?.onCategoryChanged(currentCategories!![i])
            }
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

    override fun onStart() {
        super.onStart()
        CategoryManager.addUpdateListener(this)
    }

    override fun onStop() {
        super.onStop()
        CategoryManager.removeUpdateListener(this)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? FilterListener
    }
}

interface FilterListener {
    fun onCategoryChanged(category: ArchiveCategory)
    fun onSortChanged(sort: SortMethod, desc: Boolean)
}

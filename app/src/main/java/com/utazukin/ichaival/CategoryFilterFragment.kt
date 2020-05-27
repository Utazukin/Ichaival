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

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.fragment.app.Fragment

class CategoryFilterFragment : Fragment() {
    private lateinit var parentView: ViewGroup
    private lateinit var categoryGroup: RadioGroup
    private lateinit var currentCategories: List<ArchiveCategory>
    private var listener: CategoryListener? = null
    private val categoryButtons = mutableListOf<AppCompatRadioButton>()
    val selectedCategory: ArchiveCategory?
        get() {
            if (categoryGroup.checkedRadioButtonId >= 0)
                return currentCategories[categoryGroup.checkedRadioButtonId]
            return null
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        parentView = inflater.inflate(R.layout.fragment_category_filter, container, false) as ViewGroup
        return parentView
    }

    fun initCategories(categories: List<ArchiveCategory>) {
        categoryGroup = RadioGroup(context)
        currentCategories = categories

        categoryButtons.clear()
        for ((i, category) in categories.withIndex()) {
            val categoryButton = AppCompatRadioButton(context)
            categoryButton.text = category.name
            categoryButton.id = i
            categoryGroup.addView(categoryButton)
            categoryButtons.add(categoryButton)
        }

        categoryGroup.setOnCheckedChangeListener { _, i ->
            if (i >= 0 && categoryButtons[i].isChecked) //Check if really checked due to a bug in clearCheck().
                listener?.onCategoryChanged(currentCategories[i])
        }

        parentView.addView(categoryGroup)
    }

    fun clearCategory() = categoryGroup.clearCheck()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? CategoryListener
    }
}

interface CategoryListener {
    fun onCategoryChanged(category: ArchiveCategory)
}

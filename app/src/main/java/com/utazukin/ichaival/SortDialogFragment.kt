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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioGroup
import androidx.fragment.app.DialogFragment

private const val SORT_KEY = "sort_method"

enum class SortMethod(val value: Int) {
    Alpha(1),
    Date(2);

    companion object {
        private val map by lazy { SortMethod.values().associateBy(SortMethod::value)}
        fun fromInt(type: Int) = map[type]
    }
}

class SortDialogFragment : DialogFragment() {
    private lateinit var sortGroup: RadioGroup
    private lateinit var saveButton: Button
    private var sortMethod: SortMethod = SortMethod.Alpha
    private var changeListener: ((method: SortMethod) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.sort_popup_layout, container, false)

        arguments?.run { sortMethod = SortMethod.fromInt(getInt(SORT_KEY, 1)) ?: SortMethod.Alpha }

        view?.run {
            sortGroup = findViewById(R.id.sort_group)

            sortGroup.setOnCheckedChangeListener { _, id -> sortMethod = getMethodFromId(id) }

            saveButton = findViewById(R.id.save_button)
            saveButton.setOnClickListener { changeListener?.invoke(sortMethod); dismiss() }

            when(sortMethod) {
                SortMethod.Alpha -> sortGroup.check(R.id.rad_alpha)
                SortMethod.Date -> sortGroup.check(R.id.rad_date)
            }
        }
        return view
    }

    private fun getMethodFromId(id: Int) : SortMethod {
        return when(id) {
            R.id.rad_alpha -> SortMethod.Alpha
            R.id.rad_date -> SortMethod.Date
            else -> SortMethod.Alpha
        }
    }

    fun setSortChangeListener(listener: (method: SortMethod) -> Unit) {
        changeListener = listener
    }

    companion object {
        @JvmStatic
        fun createInstance(currentSort: SortMethod) : SortDialogFragment {
            return SortDialogFragment().apply {
                arguments = Bundle().apply {putInt(SORT_KEY, currentSort.value)}
            }
        }
    }
}
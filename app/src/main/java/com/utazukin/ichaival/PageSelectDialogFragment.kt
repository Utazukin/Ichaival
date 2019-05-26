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
import android.widget.NumberPicker
import androidx.fragment.app.DialogFragment

typealias PageSelectedListener = (Int) -> Unit

class PageSelectDialogFragment : DialogFragment() {
    private lateinit var cancelButton: Button
    private lateinit var okButton: Button
    private lateinit var pageSelector: NumberPicker
    var listener: PageSelectedListener? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.goto_page_layout, container, false)
        okButton = view.findViewById(R.id.ok_button)
        cancelButton = view.findViewById(R.id.cancel_button)
        pageSelector = view.findViewById(R.id.page_picker)

        arguments?.let {
            pageSelector.minValue = 1
            pageSelector.maxValue = it.getInt(MAX_PARAM)
            pageSelector.value = it.getInt(CURRENT_PARAM)
        }

        okButton.setOnClickListener{ listener?.invoke(pageSelector.value - 1); dismiss() }
        cancelButton.setOnClickListener{ dismiss() }

        return view
    }

    companion object {
        private const val CURRENT_PARAM = "current"
        private const val MAX_PARAM = "max"

        fun createInstance(currentPage: Int, maxPage: Int) : PageSelectDialogFragment {
           val bundle = Bundle().apply {
               putInt(CURRENT_PARAM, currentPage)
               putInt(MAX_PARAM, maxPage)
           }
            return PageSelectDialogFragment().apply { arguments = bundle }
        }
    }
}
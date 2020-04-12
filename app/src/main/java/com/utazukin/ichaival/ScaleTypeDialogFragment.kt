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


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.fragment.app.DialogFragment

enum class ScaleType(val value: Int) {
    FitPage(0),
    FitHeight(1),
    FitWidth(2);

    companion object {
        private val map by lazy { values().associateBy(ScaleType::value)}
        fun fromInt(type: Int) = map[type]
    }
}

typealias ScaleListener = (ScaleType) -> Unit

private const val SCALE_PARAM = "scale_type"


class ScaleTypeDialogFragment : DialogFragment() {
    var listener: ScaleListener? = null
    private var currentScale = ScaleType.FitPage
    private val scaleButtons = mutableListOf<RadioButton>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_scale_type_dialog, container, false)

        scaleButtons.add(view.findViewById(R.id.fit_page_button))
        scaleButtons.add(view.findViewById(R.id.fit_height_button))
        scaleButtons.add(view.findViewById(R.id.fit_width_button))

        arguments?.run {
            currentScale = ScaleType.fromInt(getInt(SCALE_PARAM, 0))!!
        }

        for ((i, button) in scaleButtons.withIndex()) {
            button.setOnClickListener { listener?.invoke(ScaleType.fromInt(i)!!); dismiss() }
            button.isChecked = i == currentScale.value
        }

        return view
    }

    companion object {
        @JvmStatic
        fun newInstance(scale: ScaleType) = ScaleTypeDialogFragment().apply {
            arguments = Bundle().apply { putInt(SCALE_PARAM, scale.value) }
        }
    }
}

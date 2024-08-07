/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2024 Utazukin
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

package com.utazukin.ichaival.reader

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.Resources
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Spinner
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.utazukin.ichaival.R
import com.utazukin.ichaival.ServerManager

enum class ScaleType(val value: Int) {
    FitPage(0),
    FitHeight(1),
    FitWidth(2),
    Webtoon(3);

    companion object {
        private val map by lazy { entries.associateBy(ScaleType::value)}
        fun fromInt(type: Int) = map[type] ?: FitPage
        fun fromString(type: String?, resources: Resources) : ScaleType {
            return when (type) {
                resources.getString(R.string.page_scale_type) -> FitPage
                resources.getString(R.string.height_scale_type) -> FitHeight
                resources.getString(R.string.width_scale_type) -> FitWidth
                resources.getString(R.string.webtoon_scale_type) -> Webtoon
                else -> FitPage
            }
        }
    }
}

class ReaderSettingsDialogFragment : BottomSheetDialogFragment() {
    private var handler: ReaderSettingsHandler? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_reader_settings_dialog, container, false)

        setupSpinner(view)
        val detailsButton: Button = view.findViewById(R.id.detail_button)
        val gotoButton: Button = view.findViewById(R.id.goto_button)
        val thumbButton: Button = view.findViewById(R.id.thumb_button)
        val refreshButton: Button = view.findViewById(R.id.refresh_button)
        val bookmarkButton: Button = view.findViewById(R.id.bookmark_button)
        val randomButton: Button = view.findViewById(R.id.random_archive_button)

        if (!ServerManager.canEdit)
            thumbButton.visibility = View.GONE

        detailsButton.setOnClickListener{ handler?.handleButton(R.id.detail_button) }
        gotoButton.setOnClickListener { handler?.handleButton(R.id.goto_button) }
        thumbButton.setOnClickListener { handler?.handleButton(R.id.thumb_button) }
        refreshButton.setOnClickListener{ handler?.handleButton(R.id.refresh_button) }
        bookmarkButton.setOnClickListener { handler?.handleButton(R.id.bookmark_button) }
        randomButton.setOnClickListener { handler?.handleButton(R.id.random_archive_button) }
        with(requireDialog() as BottomSheetDialog) { dismissWithAnimation = true }

        return view
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            setOnShowListener {
                val d = it as BottomSheetDialog
                val bottom: FrameLayout? = d.findViewById(com.google.android.material.R.id.design_bottom_sheet)
                if (bottom != null)
                    BottomSheetBehavior.from(bottom).state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    private fun setupSpinner(view: View) {
        val currentScale = arguments?.run { ScaleType.fromInt(getInt(SCALE_PARAM, 0)) } ?: ScaleType.FitPage

        val spinner: Spinner = view.findViewById(R.id.scale_type_spinner)
        spinner.setSelection(currentScale.value)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                handler?.updateScaleType(ScaleType.fromInt(position))
            }

        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        handler?.onClose()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        handler = context as? ReaderSettingsHandler
    }

    companion object {
        private const val SCALE_PARAM = "scale_type"

        fun newInstance(scaleType: ScaleType) = ReaderSettingsDialogFragment().apply {
            arguments = Bundle().apply {
                putInt(SCALE_PARAM, scaleType.value)
            }
        }
    }
}

interface ReaderSettingsHandler {
    fun updateScaleType(type: ScaleType)
    fun handleButton(buttonId: Int)
    fun onClose()
}

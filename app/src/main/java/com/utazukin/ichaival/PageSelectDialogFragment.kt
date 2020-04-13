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
import android.widget.Button
import android.widget.ImageView
import android.widget.NumberPicker
import androidx.fragment.app.DialogFragment
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

typealias PageSelectedListener = (Int) -> Unit

class PageSelectDialogFragment : DialogFragment() {
    var listener: PageSelectedListener? = null
    private var coroutineScope: CoroutineScope? = null
    private val requestOptions = RequestOptions().override(getDpAdjusted(300))
    private var previewJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.goto_page_layout, container, false)
        val okButton: Button = view.findViewById(R.id.ok_button)
        val cancelButton: Button = view.findViewById(R.id.cancel_button)
        val pageSelector: NumberPicker = view.findViewById(R.id.page_picker)
        val preview: ImageView = view.findViewById(R.id.picker_preview)

        var arcId: String? = null
        arguments?.let {
            pageSelector.minValue = 1
            pageSelector.maxValue = it.getInt(MAX_PARAM)
            pageSelector.value = it.getInt(CURRENT_PARAM)
            arcId = it.getString(ID_PARAM)
        }

        coroutineScope?.launch {
            val archive = arcId?.let { DatabaseReader.getArchive(it) }
            pageSelector.setOnValueChangedListener { _, _, newValue ->
                previewJob?.cancel()
                previewJob = coroutineScope?.launch {
                    delay(400)
                    val imageUrl = archive?.getPageImage(newValue - 1)
                    Glide.with(context!!)
                        .load(imageUrl)
                        .apply(requestOptions)
                        .into(preview)
                }
            }

            Glide.with(context!!)
                .load(archive?.getPageImage(pageSelector.value - 1))
                .apply(requestOptions)
                .into(preview)
        }

        okButton.setOnClickListener{ listener?.invoke(pageSelector.value - 1); dismiss() }
        cancelButton.setOnClickListener{ dismiss() }

        return view
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        coroutineScope = context as? CoroutineScope
    }

    override fun onDetach() {
        super.onDetach()
        previewJob?.cancel()
    }

    companion object {
        private const val CURRENT_PARAM = "current"
        private const val MAX_PARAM = "max"
        private const val ID_PARAM = "id"

        fun createInstance(currentPage: Int, maxPage: Int, arcId: String) : PageSelectDialogFragment {
           val bundle = Bundle().apply {
               putInt(CURRENT_PARAM, currentPage)
               putInt(MAX_PARAM, maxPage)
               putString(ID_PARAM, arcId)
           }
            return PageSelectDialogFragment().apply { arguments = bundle }
        }
    }
}
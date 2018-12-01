/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2018 Utazukin
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
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.github.chrisbanes.photoview.PhotoView
import java.io.FileNotFoundException


class ReaderFragment : Fragment() {
    private var listener: OnFragmentInteractionListener? = null
    private var imageToDisplay: String? = null
    private var isAttached = false
    private var page = 0
    private lateinit var mainImage: PhotoView
    private lateinit var pageNum: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view =  inflater.inflate(R.layout.fragment_reader, container, false)

        mainImage = view.findViewById(R.id.main_image)
        mainImage.setOnPhotoTapListener { _, _, _ -> listener?.onFragmentTap() }
        mainImage.setOnOutsidePhotoTapListener{ listener?.onFragmentTap() }


        pageNum = view.findViewById(R.id.page_num)
        pageNum.text = (page + 1).toString()
        pageNum.visibility = View.VISIBLE

        progressBar = view.findViewById(R.id.progressBar)
        progressBar.visibility = View.VISIBLE

        return view
    }

    fun displayImage(image: String?, page: Int) {
        this.page = page
        pageNum.text = (page + 1).toString()
        if (!isAttached)
           imageToDisplay = image
        else {
            Glide.with(activity!!).asBitmap().load(image)
                .apply(RequestOptions().override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL))
                .addListener(object: RequestListener<Bitmap>{
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Bitmap>?,
                        isFirstResource: Boolean
                    ): Boolean {
                        if (e?.rootCauses?.any { x -> x is FileNotFoundException} == true) {
                            listener?.onImageLoadError()
                            return true
                        }
                        return false
                    }

                    override fun onResourceReady(
                        resource: Bitmap?,
                        model: Any?,
                        target: Target<Bitmap>?,
                        dataSource: DataSource?,
                        isFirstResource: Boolean
                    ): Boolean {
                        pageNum.visibility = View.GONE
                        progressBar.visibility = View.GONE
                        return false
                    }

                })
                .into(mainImage)
        }
    }

    override fun onDetach() {
        super.onDetach()
        isAttached = false
    }

    override fun onAttach(context: Context?) {
        super.onAttach(context)
        if (context is OnFragmentInteractionListener) {
            isAttached = true
            listener = context

            if (imageToDisplay != null) {
                val image = imageToDisplay
                imageToDisplay = null
                displayImage(image, page)
            }
        } else {
            throw RuntimeException(context.toString() + " must implement OnFragmentInteractionListener")
        }
    }

    interface OnFragmentInteractionListener {
        fun onFragmentTap()

        fun onImageLoadError()
    }

}

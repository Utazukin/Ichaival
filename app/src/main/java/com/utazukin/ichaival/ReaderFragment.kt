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


import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.chrisbanes.photoview.PhotoView
import com.github.piasy.biv.loader.ImageLoader
import com.github.piasy.biv.loader.glide.GlideLoaderException
import com.github.piasy.biv.view.BigImageView
import java.io.File

enum class TouchZone {
    Left,
    Right,
    Center
}


class ReaderFragment : Fragment() {
    private var listener: OnFragmentInteractionListener? = null
    private var imageToDisplay: String? = null
    private var isAttached = false
    private var page = 0
    private var imagePath: String? = null
    private lateinit var mainImage: BigImageView
    private lateinit var pageNum: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var imageLoader: GlidePhotoViewFactory
    private var retryCount = 0

    private val drawableLoaderCallback by lazy {
        val fragment = this
        object : ImageLoader.Callback {
            override fun onFinish() {
            }

            override fun onSuccess(image: File?) {
                pageNum.visibility = View.GONE
                progressBar.visibility = View.GONE
                setupImageTapEvents()
                retryCount = 0
            }

            override fun onFail(error: Exception?) {
                if (error is GlideLoaderException && retryCount < 3) {
                    listener?.onImageLoadError(fragment)
                    ++retryCount
                }
            }

            override fun onCacheHit(imageType: Int, image: File?) {
            }

            override fun onCacheMiss(imageType: Int, image: File?) {
            }

            override fun onProgress(progress: Int) {
            }

            override fun onStart() {
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view =  inflater.inflate(R.layout.fragment_reader, container, false)

        arguments?.let {
            page = it.getInt(PAGE_NUM)
        }

        mainImage = view.findViewById(R.id.main_image)
        mainImage.setInitScaleType(BigImageView.INIT_SCALE_TYPE_CENTER_INSIDE)
        mainImage.setImageLoaderCallback(drawableLoaderCallback)
        retryCount = 0

        imageLoader = GlidePhotoViewFactory()
        mainImage.setImageViewFactory(imageLoader)

        pageNum = view.findViewById(R.id.page_num)
        pageNum.text = (page + 1).toString()
        pageNum.visibility = View.VISIBLE

        progressBar = view.findViewById(R.id.progressBar)
        progressBar.visibility = View.VISIBLE

        return view
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupImageTapEvents() {
        val view: View? = mainImage.ssiv ?: imageLoader.photoView
        if (view is SubsamplingScaleImageView) {
            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
                    if (view.isReady && e != null)
                        listener?.onFragmentTap(getTouchZone(e.x))
                    return true
                }
            })

            view.setOnTouchListener { _, e -> gestureDetector.onTouchEvent(e) }
        }
        else if (view is PhotoView) {
            view.setOnViewTapListener { _, x, _ -> listener?.onFragmentTap(getTouchZone(x)) }
        }
    }

    fun displayImage(image: String?, page: Int) {
        this.page = page
        pageNum.text = (page + 1).toString()
        if (!isAttached)
           imageToDisplay = image
        else {
            imagePath = image

            if (image != null)
                mainImage.showImage(Uri.parse(image))
        }
    }

    fun reloadImage() {
       if (imagePath != null)
           displayImage(imagePath, page)
    }

    private fun getTouchZone(x: Float) : TouchZone {
        val location = x / mainImage.width

        if (location <= 0.4)
            return TouchZone.Left

        if (location >= 0.6)
            return TouchZone.Right

        return TouchZone.Center
    }

    override fun onDetach() {
        super.onDetach()
        isAttached = false
        mainImage.ssiv?.recycle()
    }

    override fun onAttach(context: Context) {
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
            throw RuntimeException("$context must implement OnFragmentInteractionListener")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", page)
        outState.putString("pagePath", imagePath)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        savedInstanceState?.run {
            page = getInt("page")
            displayImage(getString("pagePath"), page)
        }
    }

    interface OnFragmentInteractionListener {
        fun onFragmentTap(zone: TouchZone)

        fun onImageLoadError(fragment: ReaderFragment)
    }

    companion object {
        private const val PAGE_NUM = "page"

        @JvmStatic
        fun createInstance(page: Int) =
            ReaderFragment().apply {
                arguments = Bundle().apply {
                    putInt(PAGE_NUM, page)
                }
            }
    }
}

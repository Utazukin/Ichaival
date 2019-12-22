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
import android.os.Bundle
import android.view.*
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

enum class TouchZone {
    Left,
    Right,
    Center
}

class ReaderFragment : Fragment() {
    private var listener: OnFragmentInteractionListener? = null
    private var page = 0
    private var imagePath: String? = null
    private var mainImage: View? = null
    private lateinit var pageNum: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var topLayout: RelativeLayout
    private var retryCount = 0
    private var createViewCalled = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view =  inflater.inflate(R.layout.fragment_reader, container, false)

        arguments?.run {
            page = getInt(PAGE_NUM)
        }

        retryCount = 0
        topLayout = view.findViewById(R.id.reader_layout)
        pageNum = view.findViewById(R.id.page_num)
        pageNum.text = (page + 1).toString()
        pageNum.visibility = View.VISIBLE

        progressBar = view.findViewById(R.id.progressBar)
        progressBar.visibility = View.VISIBLE

        //Tapping the view will display the toolbar until the image is displayed.
        view.setOnClickListener { listener?.onFragmentTap(TouchZone.Center) }

        imagePath?.let(::displayImage)
        createViewCalled = true
        return view
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupImageTapEvents(view: View) {
        if (view is SubsamplingScaleImageView) {
            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
                    if (view.isReady && e != null)
                        listener?.onFragmentTap(getTouchZone(e.x, view))
                    return true
                }
            })

            view.setOnTouchListener { _, e -> gestureDetector.onTouchEvent(e) }
        }
        else if (view is PhotoView)
            view.setOnViewTapListener { _, x, _ -> listener?.onFragmentTap(getTouchZone(x, view)) }
    }

    private fun displayImage(image: String) {
        imagePath = image

        //TODO use something better to detect this.
        mainImage = if (image.endsWith(".gif")) {
            PhotoView(activity).also {
                initializeView(it)
                Glide.with(activity!!)
                    .load(image)
                    .apply(RequestOptions().override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL))
                    .addListener(getListener())
                    .into(it)
            }
        } else {
            SubsamplingScaleImageView(activity).also {
                initializeView(it)

                it.setMaxTileSize(getMaxTextureSize())
                it.setMinimumTileDpi(160)

                Glide.with(activity!!)
                    .downloadOnly()
                    .load(image)
                    .addListener(getListener(false))
                    .into (SubsamplingTarget(it) {
                        pageNum.visibility = View.GONE
                        progressBar.visibility = View.GONE
                        view?.setOnClickListener(null)
                    })
            }
        }.also { setupImageTapEvents(it) }
    }

    private fun initializeView(view: View) {
        view.background = resources.getDrawable(android.R.color.black)
        val layoutParams = RelativeLayout
            .LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
        view.layoutParams = layoutParams
        topLayout.addView(view)
        pageNum.bringToFront()
        progressBar.bringToFront()
    }

    private fun <T> getListener(clearOnReady: Boolean = true) : RequestListener<T> {
        val fragment = this
        return object: RequestListener<T> {
            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<T>?,
                isFirstResource: Boolean
            ): Boolean {
                if (e?.rootCauses?.any { x -> x is FileNotFoundException } == true && retryCount < 3) {
                    listener?.onImageLoadError(fragment)
                    ++retryCount
                    return true
                }
                return false
            }

            override fun onResourceReady(
                resource: T?,
                model: Any?,
                target: Target<T>?,
                dataSource: DataSource?,
                isFirstResource: Boolean
            ): Boolean {
                if (clearOnReady) {
                    pageNum.visibility = View.GONE
                    view?.setOnClickListener(null)
                }
                progressBar.visibility = View.GONE
                return false
            }
        }
    }

    fun reloadImage() {
        imagePath?.let { displayImage(it) }
    }

    private fun getTouchZone(x: Float, view: View) : TouchZone {
        val location = x / view.width

        if (location <= 0.4)
            return TouchZone.Left

        if (location >= 0.6)
            return TouchZone.Right

        return TouchZone.Center
    }

    override fun onDetach() {
        super.onDetach()
        createViewCalled = false
        (mainImage as? SubsamplingScaleImageView)?.recycle()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        val activity = context as ReaderActivity
        listener = activity

        arguments?.run {
            val page = getInt(PAGE_NUM)
            activity.launch {
                val image = withContext(Dispatchers.Default) { activity.archive?.getPageImage(page) }
                if (image != null) {
                    if (createViewCalled)
                        displayImage(image)
                    else
                        imagePath = image
                }
            }
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
            imagePath = getString("pagePath")
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

/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2023 Utazukin
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
import android.content.res.Configuration
import android.graphics.PointF
import android.os.Bundle
import android.view.*
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class TouchZone {
    Left,
    Right,
    Center
}

interface PageFragment {
    fun onArchiveLoad(archive: Archive)
    fun onScaleTypeChange(scaleType: ScaleType)
    fun reloadImage()
}

class ReaderFragment : Fragment(), PageFragment {
    private var listener: OnFragmentInteractionListener? = null
    private var page = 0
    private var imagePath: String? = null
    private var mainImage: View? = null
    private lateinit var pageNum: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var topLayout: RelativeLayout
    private lateinit var failedMessage: TextView
    private var createViewCalled = false
    private val currentScaleType
        get() = (activity as? ReaderActivity)?.currentScaleType

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        val view =  inflater.inflate(R.layout.fragment_reader, container, false)

        arguments?.run {
            page = getInt(PAGE_NUM)
        }

        topLayout = view.findViewById(R.id.reader_layout)
        pageNum = view.findViewById(R.id.page_num)
        pageNum.text = (page + 1).toString()
        pageNum.visibility = View.VISIBLE

        progressBar = view.findViewById(R.id.progressBar)
        progressBar.isIndeterminate = true
        progressBar.visibility = View.VISIBLE

        failedMessage = view.findViewById(R.id.failed_message)
        failedMessage.setOnClickListener { listener?.onImageLoadError() }
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                listener?.onFragmentTap(getTouchZone(e.x, view))
                return true
            }
        })
        failedMessage.setOnTouchListener { _, motionEvent ->
            gestureDetector.onTouchEvent(motionEvent)
            true
        }

        //Tapping the view will display the toolbar until the image is displayed.
        view.setOnClickListener { listener?.onFragmentTap(TouchZone.Center) }
        view.setOnLongClickListener { listener?.onFragmentLongPress() == true }

        imagePath?.let(::displayImage)
        createViewCalled = true
        return view
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScaleType(mainImage, currentScaleType, true)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupImageTapEvents(view: View) {
        if (view is SubsamplingScaleImageView) {
            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (view.isReady)
                        listener?.onFragmentTap(getTouchZone(e.x, view))
                    return true
                }
            })

            view.setOnTouchListener { _, e -> gestureDetector.onTouchEvent(e) }
        }
        else if (view is PhotoView)
            view.setOnViewTapListener { _, x, _ -> listener?.onFragmentTap(getTouchZone(x, view)) }

        view.setOnLongClickListener { listener?.onFragmentLongPress() == true }
    }

    private fun displayImage(image: String) {
        imagePath = image

        progressBar.isIndeterminate = false
        lifecycleScope.launch {
            val imageFile = withContext(Dispatchers.IO) {
                var target: Target<File>? = null
                try {
                    target = downloadImageWithProgress(requireActivity(), image) {
                        progressBar.progress = it
                    }
                    target.get()
                } catch (e: Exception) {
                    null
                } finally {
                    activity?.let { Glide.with(it).clear(target) }
                }
            }

            if (imageFile == null) {
                showErrorMessage()
                return@launch
            }

            val format = getImageFormat(imageFile)
            mainImage = if (format == ImageFormat.GIF) {
                PhotoView(activity).also {
                    initializeView(it)
                    Glide.with(requireActivity())
                        .load(imageFile)
                        .apply(RequestOptions().override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL))
                        .addListener(getListener())
                        .into(it)
                }
            } else {
                SubsamplingScaleImageView(activity).also {
                    initializeView(it)

                    it.setMaxTileSize(getMaxTextureSize())
                    it.setMinimumTileDpi(160)

                    if (format != null) {
                        it.setBitmapDecoderClass(ImageDecoder::class.java)
                        it.setRegionDecoderClass(ImageRegionDecoder::class.java)
                    }

                    it.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            pageNum.visibility = View.GONE
                            progressBar.visibility = View.GONE
                            view?.run {
                                setOnClickListener(null)
                                setOnLongClickListener(null)
                            }
                            updateScaleType(it, currentScaleType)
                        }
                        override fun onImageLoadError(e: Exception?) {
                            showErrorMessage()
                            it.visibility = View.GONE
                        }
                    })

                    it.setImage(ImageSource.uri(imageFile.absolutePath))
                }
            }.also { setupImageTapEvents(it) }
        }

    }

    private fun initializeView(view: View) {
        view.background = ContextCompat.getDrawable(requireActivity(), android.R.color.black)
        val layoutParams = RelativeLayout
            .LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
        view.layoutParams = layoutParams
        topLayout.addView(view)
        pageNum.bringToFront()
        progressBar.bringToFront()
    }

    private fun <T> getListener(clearOnReady: Boolean = true) : RequestListener<T> {
        return object: RequestListener<T> {
            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<T>?,
                isFirstResource: Boolean
            ): Boolean {
                showErrorMessage()
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
                    view?.run {
                        setOnClickListener(null)
                        setOnLongClickListener(null)
                    }
                }
                progressBar.visibility = View.GONE
                return false
            }
        }
    }

    override fun reloadImage() {
        imagePath?.let { displayImage(it) }
    }

    private fun updateScaleType(newScale: ScaleType) = updateScaleType(mainImage, newScale)

    private fun updateScaleType(imageView: View?, scaleType: ScaleType?, useOppositeOrientation: Boolean = false) {
        when (imageView) {
            is SubsamplingScaleImageView -> {
                when (scaleType) {
                    ScaleType.FitPage, null -> {
                        imageView.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
                        imageView.resetScaleAndCenter()
                    }
                    ScaleType.FitHeight -> {
                        val vPadding = imageView.paddingBottom - imageView.paddingTop
                        val viewHeight = if (useOppositeOrientation) imageView.width else imageView.height
                        val minScale = (viewHeight - vPadding) / imageView.sHeight.toFloat()
                        imageView.minScale = minScale
                        imageView.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CUSTOM)
                        imageView.setScaleAndCenter(minScale, PointF(0f, 0f))
                    }
                    ScaleType.FitWidth, ScaleType.Webtoon -> {
                        val hPadding = imageView.paddingLeft - imageView.paddingRight
                        val viewWidth = if (useOppositeOrientation) imageView.height else imageView.width
                        val minScale = (viewWidth - hPadding) / imageView.sWidth.toFloat()
                        imageView.minScale = minScale
                        imageView.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CUSTOM)
                        imageView.setScaleAndCenter(minScale, PointF(0f, 0f))
                    }
                }
            }
            is PhotoView -> {
                //TODO
            }
        }
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
        (activity as? ReaderActivity)?.unregisterPage(this)
        (mainImage as? SubsamplingScaleImageView)?.recycle()
    }

    override fun onScaleTypeChange(scaleType: ScaleType) = updateScaleType(scaleType)

    override fun onArchiveLoad(archive: Archive) {
        arguments?.run {
            val page = getInt(PAGE_NUM)
            lifecycleScope.launch {
                val image = archive.getPageImage(requireContext(), page)
                if (image != null) {
                    if (createViewCalled)
                        displayImage(image)
                    else
                        imagePath = image
                } else
                    showErrorMessage()
            }
        }
    }

    private fun showErrorMessage() {
        failedMessage.visibility = View.VISIBLE
        pageNum.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        val activity = context as ReaderActivity
        listener = activity

        activity.registerPage(this)
        activity.archive?.let { onArchiveLoad(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.run {
            putInt("page", page)
            putString("pagePath", imagePath)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        savedInstanceState?.run {
            page = getInt("page")
            imagePath = getString("pagePath")
        }
    }

    interface OnFragmentInteractionListener {
        fun onFragmentTap(zone: TouchZone)
        fun onImageLoadError()
        fun onFragmentLongPress() : Boolean
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

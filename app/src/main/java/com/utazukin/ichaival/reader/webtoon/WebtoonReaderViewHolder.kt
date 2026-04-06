/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2025 Utazukin
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

package com.utazukin.ichaival.reader.webtoon

import android.annotation.SuppressLint
import android.graphics.PointF
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.size.Dimension
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.chrisbanes.photoview.PhotoView
import com.utazukin.ichaival.Archive
import com.utazukin.ichaival.ImageDecoder
import com.utazukin.ichaival.ImageFormat
import com.utazukin.ichaival.ImageRegionDecoder
import com.utazukin.ichaival.R
import com.utazukin.ichaival.cacheOrGet
import com.utazukin.ichaival.createGifLoader
import com.utazukin.ichaival.downloadCoilImageWithProgress
import com.utazukin.ichaival.getImageFormat
import com.utazukin.ichaival.getMaxTextureSize
import com.utazukin.ichaival.isAnimatedImage
import com.utazukin.ichaival.reader.PageFragment
import com.utazukin.ichaival.reader.ReaderActivity
import com.utazukin.ichaival.reader.ScaleType
import com.utazukin.ichaival.reader.TouchToggleLayout
import com.utazukin.ichaival.reader.TouchZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WebtoonReaderViewHolder(private val view: View, private val activity: ReaderActivity) : RecyclerView.ViewHolder(view), PageFragment {
    private var page = 0
    private var imagePath: String? = null
    private var mainImage: View? = null
    private val pageNum: TextView = view.findViewById(R.id.page_num)
    private val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
    private val topLayout: TouchToggleLayout = view.findViewById(R.id.reader_layout)
    private val failedMessage: TextView
    private val jobs = mutableListOf<Job>()
    private val loader = activity.loader
    private val gifLoader by lazy { loader.createGifLoader() }

    init {
        progressBar.isIndeterminate = true
        progressBar.visibility = View.VISIBLE

        failedMessage = view.findViewById(R.id.failed_message)
        failedMessage.setOnClickListener { activity.onImageLoadError() }
        //Tapping the view will display the toolbar until the image is displayed.
        view.setOnClickListener { activity.onFragmentTap(TouchZone.Center) }
        view.setOnLongClickListener { activity.onFragmentLongPress() }

        topLayout.layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT)
        topLayout.enableTouch = false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupImageTapEvents(view: View) {
        view.setOnLongClickListener { activity.onFragmentLongPress() }
    }

    private fun loadGif(imageFile: File, image: String, imageView: PhotoView) {
        imageView.load(imageFile, gifLoader) {
            diskCacheKey(image)
            size(Dimension.Undefined, Dimension.Undefined)
            listener(
                    onSuccess = { _, _ ->
                        pageNum.visibility = View.GONE
                        with(view) {
                            setOnClickListener(null)
                            setOnLongClickListener(null)
                        }
                        progressBar.visibility = View.GONE
                    },
                    onError = { _, _ -> showErrorMessage() }
            )
        }
    }

    private fun displayImage(image: String) {
        imagePath = image

        progressBar.isIndeterminate = false
        val job = activity.lifecycleScope.launch {
            val imageFile = withContext(Dispatchers.IO) {
                val request = downloadCoilImageWithProgress(activity, image) {
                    progressBar.progress = it
                }
                loader.cacheOrGet(request)
            }

            if (imageFile == null) {
                showErrorMessage()
                return@launch
            }

            val format = getImageFormat(imageFile)
            mainImage?.let {
                when (it) {
                    is PhotoView -> {
                        loadGif(imageFile, image, it)
                        return@launch
                    }
                    is SubsamplingScaleImageView -> {
                        it.setImage(ImageSource.uri(imageFile.absolutePath))
                        return@launch
                    }
                    else -> {}
                }
            }

            mainImage = if (isAnimatedImage(imageFile)) {
                PhotoView(activity).also {
                    initializeView(it)
                    loadGif(imageFile, image, it)
                }
            } else {
                WebtoonSubsamplingImageView(activity).also {
                    initializeView(it)

                    it.setMaxTileSize(getMaxTextureSize())
                    it.setMinimumTileDpi(180)
                    it.maxScale = 5f

                    if (format != null) {
                        it.setBitmapDecoderClass(ImageDecoder::class.java)
                        it.setRegionDecoderClass(ImageRegionDecoder::class.java)
                    }

                    it.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            pageNum.visibility = View.GONE
                            progressBar.visibility = View.GONE
                            view.run {
                                setOnClickListener(null)
                                setOnLongClickListener(null)
                            }
                            updateScaleType(it)
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
        jobs.add(job)
    }

    private fun initializeView(view: View) {
        view.background = ContextCompat.getDrawable(activity, android.R.color.black)
        val layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        )
        view.layoutParams = layoutParams
        topLayout.addView(view)
        pageNum.bringToFront()
        progressBar.bringToFront()
    }

    override fun reloadImage() {
        imagePath?.let { displayImage(it) }
    }

    private fun updateScaleType() = updateScaleType(mainImage)

    private fun updateScaleType(imageView: View?) = imageView?.post {
        when (imageView) {
            is SubsamplingScaleImageView -> {
                val hPadding = imageView.paddingLeft - imageView.paddingRight
                val viewWidth = imageView.width
                val minScale = (viewWidth - hPadding) / imageView.sWidth.toFloat()
                with(imageView) {
                    this.minScale = minScale
                    setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CUSTOM)
                    setScaleAndCenter(minScale, PointF(0f, 0f))
                }
            }
            is PhotoView -> {
                val vPadding = imageView.paddingBottom - imageView.paddingTop
                val viewHeight = imageView.width
                val minScale = (viewHeight - vPadding) / imageView.drawable.intrinsicWidth.toFloat()
                with(imageView) {
                    maximumScale = minScale * 3
                    mediumScale = minScale * 1.75f
                    minimumScale = minScale
                    this.scaleType = ImageView.ScaleType.CENTER
                    setScale(minScale, 0.5f, 0.5f, false)
                }
            }
        }
    }

    fun onDetach() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        (activity as? ReaderActivity)?.unregisterPage(this)
        (mainImage as? PhotoView)?.setImageBitmap(null)
        (mainImage as? SubsamplingScaleImageView)?.recycle()
        imagePath = null
    }

    override fun onScaleTypeChange(scaleType: ScaleType) {
        updateScaleType()
    }

    override fun onArchiveLoad(archive: Archive) {
        val job = activity.launch {
            val image = archive.getPageImage(view.context, page)
            if (image != null) {
                displayImage(image)
            } else
                showErrorMessage()
        }
        jobs.add(job)
    }

    private fun showErrorMessage() {
        failedMessage.visibility = View.VISIBLE
        pageNum.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    fun onAttach(position: Int) {
        page = position
        activity.registerPage(this)
        activity.archive?.let { onArchiveLoad(it) }
    }
}
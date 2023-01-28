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
import android.graphics.PointF
import android.view.*
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WebtoonReaderViewHolder(private val context: Context,
                              private val view: View,
                              private val activity: ReaderActivity) : RecyclerView.ViewHolder(view), PageFragment {
    private var page = 0
    private var imagePath: String? = null
    private var mainImage: View? = null
    private val pageNum: TextView = view.findViewById(R.id.page_num)
    private val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
    private val topLayout: TouchToggleLayout = view.findViewById(R.id.reader_layout)
    private val failedMessage: TextView
    private val jobs = mutableListOf<Job>()

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

    private fun displayImage(image: String) {
        imagePath = image

        progressBar.isIndeterminate = false
        val job = activity.lifecycleScope.launch {
            val imageFile = withContext(Dispatchers.IO) {
                var target: Target<File>? = null
                try {
                    target = downloadImageWithProgress(activity, image) {
                        progressBar.progress = it
                    }
                    target.get()
                } catch (e: Exception) {
                    null
                } finally {
                    activity.let { Glide.with(it).clear(target) }
                }
            }

            if (imageFile == null) {
                showErrorMessage()
                return@launch
            }

            val format = getImageFormat(imageFile)
            mainImage?.let {
                when (it) {
                    is PhotoView -> {
                        Glide.with(activity)
                            .load(imageFile)
                            .apply(RequestOptions().override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL))
                            .addListener(getListener())
                            .into(it)
                        return@launch
                    }
                    is SubsamplingScaleImageView -> {
                        it.setImage(ImageSource.uri(imageFile.absolutePath))
                        return@launch
                    }
                    else -> {}
                }
            }

            mainImage = if (format == ImageFormat.GIF) {
                PhotoView(activity).also {
                    initializeView(it)
                    Glide.with(activity)
                        .load(imageFile)
                        .apply(RequestOptions().override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL))
                        .addListener(getListener())
                        .into(it)
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
                    view.run {
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

    private fun updateScaleType() = updateScaleType(mainImage)

    private fun updateScaleType(imageView: View?) = imageView?.post {
        when (imageView) {
            is SubsamplingScaleImageView -> {
                val hPadding = imageView.paddingLeft - imageView.paddingRight
                val viewWidth = imageView.width
                val minScale = (viewWidth - hPadding) / imageView.sWidth.toFloat()
                imageView.minScale = minScale
                imageView.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CUSTOM)
                imageView.setScaleAndCenter(minScale, PointF(0f, 0f))
            }
            is PhotoView -> {
                //TODO
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
        val job = activity.lifecycleScope.launch {
            val image = archive.getPageImage(context, page)
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
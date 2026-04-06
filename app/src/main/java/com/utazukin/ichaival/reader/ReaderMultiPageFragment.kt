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

package com.utazukin.ichaival.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.os.Bundle
import android.util.Size
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import coil3.ImageLoader
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
import com.utazukin.ichaival.isLocalFile
import com.utazukin.ichaival.outSize
import com.utazukin.ichaival.setDefaultScale
import com.utazukin.ichaival.tryOrNull
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import kotlin.math.ceil

enum class PageCompressFormat {
    JPEG,
    PNG;

    companion object {
        fun PageCompressFormat.toBitmapFormat() : Bitmap.CompressFormat {
            return if (this == JPEG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
        }

        fun fromString(format: String?, context: Context?) : PageCompressFormat {
            return when(format) {
                context?.getString(R.string.jpg_compress) -> JPEG
                else -> PNG
            }
        }
    }
}

class ReaderMultiPageFragment : Fragment(), PageFragment {
    private var listener: ReaderFragment.OnFragmentInteractionListener? = null
    private var page = 0
    private var otherPage = 0
    private var imagePath: String? = null
    private var otherImagePath: String? = null
    private var mainImage: View? = null
    private lateinit var pageNum: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var topLayout: RelativeLayout
    private lateinit var failedMessageText: TextView
    private lateinit var loader: ImageLoader
    private var createViewCalled = false
    private val currentScaleType
        get() = (activity as? ReaderActivity)?.currentScaleType
    private var archiveId: String? = null
    private var rtol: Boolean = false
    private var failedMessage: String? = null
    private var mergeJob: Job? = null
    private val gifLoader by lazy { loader.createGifLoader() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        val view =  inflater.inflate(R.layout.fragment_reader, container, false)

        arguments?.run {
            page = getInt(PAGE_NUM)
            otherPage = getInt(OTHER_PAGE_ID)
            archiveId = getString(ARCHIVE_ID)
        }

        with(requireActivity() as MenuHost) {
            addMenuProvider(object: MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {}
                override fun onMenuItemSelected(item: MenuItem): Boolean {
                    return when (item.itemId) {
                        R.id.swap_merged_page -> {
                            topLayout.removeView(mainImage)
                            (mainImage as? SubsamplingScaleImageView)?.recycle()
                            mainImage = null
                            pageNum.visibility = View.VISIBLE
                            progressBar.visibility = View.VISIBLE
                            progressBar.isIndeterminate = true

                            rtol = !rtol
                            imagePath?.let { displayImage(it, otherImagePath) }
                            true
                        }
                        R.id.split_merged_page -> {
                            topLayout.removeView(mainImage)
                            (mainImage as? SubsamplingScaleImageView)?.recycle()
                            mainImage = null
                            pageNum.visibility = View.VISIBLE
                            progressBar.visibility = View.VISIBLE
                            progressBar.isIndeterminate = true
                            imagePath?.let { displaySingleImage(it, otherPage, true) }
                            true
                        }
                        else -> false
                    }
                }
            }, viewLifecycleOwner, Lifecycle.State.RESUMED)
        }

        rtol = if (savedInstanceState == null) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            prefs.getBoolean(getString(R.string.rtol_pref_key), false) == !prefs.getBoolean(getString(
                R.string.dual_page_swap_key
            ), false)
        } else savedInstanceState.getBoolean(RTOL)

        topLayout = view.findViewById(R.id.reader_layout)
        pageNum = view.findViewById(R.id.page_num)
        with (pageNum) {
            text = "${page + 1}-${otherPage + 1}"
            visibility = View.VISIBLE
        }

        progressBar = view.findViewById(R.id.progressBar)
        with(progressBar) {
            isIndeterminate = true
            visibility = View.VISIBLE
        }

        //Tapping the view will display the toolbar until the image is displayed.
        with(view) {
            setOnClickListener { listener?.onFragmentTap(TouchZone.Center) }
            setOnLongClickListener { listener?.onFragmentLongPress() == true }
        }

        failedMessageText = view.findViewById(R.id.failed_message)
        failedMessageText.setOnClickListener { listener?.onImageLoadError() }
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    listener?.onFragmentTap(getTouchZone(e.x, view))
                    return true
                }
            })

        failedMessageText.setOnTouchListener { _, e -> gestureDetector.onTouchEvent(e) }

        imagePath?.let { displayImage(it, otherImagePath) }

        createViewCalled = true
        return view
    }

    private fun showErrorMessage() {
        failedMessageText.visibility = View.VISIBLE
        pageNum.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    override fun setMenuVisibility(menuVisible: Boolean) {
        super.setMenuVisibility(menuVisible)
        if (menuVisible)
            failedMessage?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }.also { failedMessage = null }
    }

    override fun onStop() {
        super.onStop()
        mergeJob?.cancel()
        mergeJob = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupImageTapEvents(view: View) {
        when (view) {
            is SubsamplingScaleImageView -> {
                val gestureDetector =
                    GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            if (view.isReady)
                                listener?.onFragmentTap(getTouchZone(e.x, view))
                            return true
                        }
                    })

                view.setOnTouchListener { _, e -> gestureDetector.onTouchEvent(e) }
            }
            is PhotoView -> view.setOnViewTapListener { _, x, _ -> listener?.onFragmentTap(getTouchZone(x, view)) }
        }

        view.setOnLongClickListener { listener?.onFragmentLongPress() == true }
    }

    private suspend fun displaySingleImageMain(image: String, failPage: Int) = withContext(Dispatchers.Main) { displaySingleImage(image, failPage) }

    private fun displaySingleImage(image: String, failPage: Int, split: Boolean = false) {
        with(activity as ReaderActivity) { onMergeFailed(page, failPage, split) }
        pageNum.text = (page + 1).toString()

        progressBar.isIndeterminate = false
        lifecycleScope.launch {
            val imageFile = withContext(Dispatchers.IO) {
                if (!isLocalFile(image)) {
                    val request = downloadCoilImageWithProgress(requireContext(), image) { progressBar.progress = it }
                    loader.cacheOrGet(request)
                } else File(image)
            }

            if (imageFile == null) {
                showErrorMessage()
                return@launch
            }

            val format = getImageFormat(imageFile)
            mainImage = if (isAnimatedImage(imageFile)) {
                PhotoView(activity).also {
                    initializeView(it)
                    it.load(imageFile, gifLoader) {
                        size(Dimension.Undefined, Dimension.Undefined)
                        listener(
                                onSuccess = { _, _ ->
                                    pageNum.visibility = View.GONE
                                    view?.run {
                                        setOnClickListener(null)
                                        setOnLongClickListener(null)
                                    }
                                    progressBar.visibility = View.GONE
                                },
                                onError = { _, _ -> showErrorMessage() }
                        )
                    }
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

    private fun createImageView(mergedPath: String, useNewDecoder: Boolean = true) {
        mainImage = SubsamplingScaleImageView(activity).apply {
            if (useNewDecoder) {
                setBitmapDecoderClass(ImageDecoder::class.java)
                setRegionDecoderClass(ImageRegionDecoder::class.java)
            }
            setOnImageEventListener(object: SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    pageNum.visibility = View.GONE
                    progressBar.visibility = View.GONE

                    view?.run {
                        setOnClickListener(null)
                        setOnLongClickListener(null)
                    }

                    updateScaleType(this@apply, currentScaleType)
                }
                override fun onImageLoadError(e: Exception?) {
                    topLayout.removeView(mainImage)
                    mainImage = null
                    recycle()
                    if (activity != null)
                        imagePath?.let { displaySingleImage(it, page) }
                }
            })
            initializeView(this)
            setMaxTileSize(getMaxTextureSize())
            setMinimumTileDpi(160)
            setImage(ImageSource.uri(mergedPath))
            setupImageTapEvents(this)
        }
    }

    private fun displayImage(image: String, otherImage: String?) {
        imagePath = image
        otherImagePath = otherImage

        if (otherImage == null) {
            displaySingleImage(image, page)
            return
        }

        mergeJob = lifecycleScope.launch(Dispatchers.Default) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val compressString = prefs.getString(getString(R.string.compression_type_pref), getString(
                R.string.jpg_compress
            ))
            val compressType = PageCompressFormat.fromString(compressString, requireContext())
            val mergedPath = DualPageHelper.getMergedPage(
                requireContext().cacheDir,
                archiveId!!,
                page,
                otherPage,
                rtol,
                compressType
            )
            if (mergedPath != null) {
                withContext(Dispatchers.Main) { createImageView(mergedPath) }
                return@launch
            }

            withContext(Dispatchers.Main) { progressBar.isIndeterminate = false }

            var targetProgess = 0
            var otherProgress = 0

            try {
                val imgFile: File?
                var otherImgFile: File? = null
                var dotherTarget: Deferred<File?>? = null

                if (!isLocalFile(image)) {
                    val target = downloadCoilImageWithProgress(requireActivity(), image) {
                        targetProgess = it / 2
                        progressBar.progress = ((targetProgess + otherProgress) * 0.9f).toInt()
                    }
                    val dtarget = async { tryOrNull { loader.cacheOrGet(target) } }
                    imgFile = dtarget.await()
                } else imgFile = File(image)

                if (!isLocalFile(otherImage)) {
                    val otherTarget = downloadCoilImageWithProgress(requireActivity(), otherImage) {
                        otherProgress = it / 2
                        progressBar.progress = ((targetProgess + otherProgress) * 0.9f).toInt()
                    }
                    dotherTarget = async { tryOrNull { loader.cacheOrGet(otherTarget) } }
                } else otherImgFile = File(otherImage)

                if (imgFile == null) {
                    displaySingleImageMain(image, page)
                    return@launch
                }
                val img = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(imgFile.absolutePath, img)
                if (isAnimatedImage(imgFile)) {
                    dotherTarget?.cancel()
                    displaySingleImageMain(image, page)
                    return@launch
                }

                if (dotherTarget != null)
                    otherImgFile = dotherTarget.await()

                if (otherImgFile == null) {
                    displaySingleImageMain(image, otherPage)
                    return@launch
                }
                val otherImg = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(otherImgFile.absolutePath, otherImg)
                if (isAnimatedImage(otherImgFile)) {
                    displaySingleImageMain(image, otherPage)
                    return@launch
                }

                if (img.outWidth > img.outHeight || otherImg.outWidth > otherImg.outHeight) {
                    val otherImageFail = otherImg.outWidth > otherImg.outHeight
                    displaySingleImageMain(image, if (otherImageFail) otherPage else page)
                } else {
                    //Scale one of the images to match the smaller one if their heights differ too much.
                    val firstImg: Size
                    val secondImg: Size
                    when {
                        img.outHeight - otherImg.outHeight < -100 -> {
                            val ar = otherImg.outWidth / otherImg.outHeight.toFloat()
                            val width = ceil(img.outHeight * ar).toInt()
                            secondImg = Size(width, img.outHeight)
                            firstImg = img.outSize
                        }
                        otherImg.outHeight - img.outHeight < -100 -> {
                            val ar = img.outWidth / img.outHeight.toFloat()
                            val width = ceil(otherImg.outHeight * ar).toInt()
                            firstImg = Size(width, otherImg.outHeight)
                            secondImg = otherImg.outSize
                        }
                        else -> {
                            firstImg = img.outSize
                            secondImg = otherImg.outSize
                        }
                    }

                    val merged = try {
                        val mergeInfo = MergeInfo(firstImg, secondImg, imgFile, otherImgFile, page, otherPage, compressType, archiveId!!, !rtol)
                        DualPageHelper.mergeBitmaps(mergeInfo, requireContext().cacheDir) { progressBar.progress = it }
                    } catch (e: Exception) { null }
                    catch (e: OutOfMemoryError) {
                        failedMessage = "Failed to merge pages: Out of Memory"
                        null
                    }
                    yield()
                    withContext(Dispatchers.Main) {
                        if (merged == null) {
                            progressBar.isIndeterminate = true
                            displaySingleImage(image, page)
                        } else {
                            progressBar.progress = 100
                            createImageView(merged)
                        }
                    }
                }
            } finally {
                loader.shutdown()
            }
        }
    }

    private fun initializeView(view: View) {
        val layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        view.layoutParams = layoutParams
        topLayout.addView(view)
        pageNum.bringToFront()
        progressBar.bringToFront()

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val bgColor = when(prefs.getString(getString(R.string.reader_bg_pref_key), getString(R.string.black_bg_color))) {
            getString(R.string.white_bg_color) -> Color.White
            getString(R.string.gray_bg_color) -> Color.Gray
            else -> Color.Black
        }
        view.setBackgroundColor(bgColor.toArgb())
    }

    override fun reloadImage() {
        imagePath?.let { displayImage(it, otherImagePath) }
    }

    private fun updateScaleType(newScale: ScaleType) = updateScaleType(mainImage, newScale)

    private fun updateScaleType(imageView: View?, scaleType: ScaleType?, useOppositeOrientation: Boolean = false) {
        when (imageView) {
            is SubsamplingScaleImageView -> {
                when (scaleType) {
                    ScaleType.FitPage, null -> imageView.setDefaultScale()
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
                if (imageView.drawable != null) {
                    when (scaleType) {
                        ScaleType.FitPage, null -> {
                            with(imageView) {
                                minimumScale = 1f
                                mediumScale = 1.75f
                                maximumScale = 3f
                                this.scaleType = ImageView.ScaleType.FIT_CENTER
                                setScale(1f, 0.5f, 0.5f, false)
                            }
                        }
                        ScaleType.FitHeight -> {
                            val vPadding = imageView.paddingBottom - imageView.paddingTop
                            val viewHeight = if (useOppositeOrientation) imageView.width else imageView.height
                            val minScale = (viewHeight - vPadding) / imageView.drawable.intrinsicHeight.toFloat()
                            with(imageView) {
                                maximumScale = minScale * 3
                                mediumScale = minScale * 1.75f
                                minimumScale = minScale
                                this.scaleType = ImageView.ScaleType.CENTER
                                setScale(minScale, 0.5f, 0.5f, false)
                            }
                        }
                        ScaleType.FitWidth, ScaleType.Webtoon -> {
                            val vPadding = imageView.paddingBottom - imageView.paddingTop
                            val viewHeight = if (useOppositeOrientation) imageView.height else imageView.width
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
            val otherPage = getInt(OTHER_PAGE_ID)
            lifecycleScope.launch {
                val image = archive.getPageImage(requireContext(), page)
                val otherImage = archive.getPageImage(requireContext(), otherPage)
                if (image != null) {
                    if (createViewCalled)
                        displayImage(image, otherImage)
                    else {
                        imagePath = image
                        otherImagePath = otherImage
                    }
                } else
                    showErrorMessage()
            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        (context as ReaderActivity).let {
            listener = it
            loader = it.loader

            it.registerPage(this)
            it.archive?.let { a -> onArchiveLoad(a) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        with(outState) {
            putInt(PAGE_NUM, page)
            putString(PAGE_PATH, imagePath)
            putInt(OTHER_PAGE_ID, otherPage)
            putBoolean(RTOL, rtol)
            if (otherImagePath != null)
                putString(OTHER_PAGE_PATH, otherImagePath)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        savedInstanceState?.run {
            page = getInt(PAGE_NUM)
            imagePath = getString(PAGE_PATH)
            otherPage = getInt(OTHER_PAGE_ID)
            otherImagePath = getString(OTHER_PAGE_PATH)
        }
    }

    companion object {
        private const val PAGE_NUM = "page"
        private const val OTHER_PAGE_ID = "other_page"
        private const val ARCHIVE_ID = "id"
        private const val PAGE_PATH = "pagePath"
        private const val OTHER_PAGE_PATH = "otherPagePath"
        private const val RTOL = "rtol"

        @JvmStatic
        fun createInstance(page: Int, otherPage: Int, archiveId: String?) =
            ReaderMultiPageFragment().apply {
                arguments = Bundle().apply {
                    putInt(PAGE_NUM, page)
                    putInt(OTHER_PAGE_ID, otherPage)
                    putString(ARCHIVE_ID, archiveId)
                }
            }
    }
}
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

package com.utazukin.ichaival.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.*
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.google.android.material.color.MaterialColors
import com.utazukin.ichaival.*
import com.utazukin.ichaival.database.DatabaseExtractListener
import com.utazukin.ichaival.database.DatabaseReader
import com.utazukin.ichaival.database.ReaderTabViewModel
import com.utazukin.ichaival.reader.ReaderFragment.OnFragmentInteractionListener
import com.utazukin.ichaival.reader.webtoon.WebtoonReaderViewHolder
import com.utazukin.ichaival.reader.webtoon.WebtoonRecyclerView
import kotlinx.coroutines.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.truncate

private const val ID_STRING = "id"
private const val PAGE_ID = "page"
private const val CURRENT_PAGE_ID = "currentPage"
private const val PROGRESS_UPDATE_DELAY = 500L //ms
private const val FORCE_REFRESH = "force"

class ReaderActivity : BaseActivity(), OnFragmentInteractionListener, TabRemovedListener,
    TabsClearedListener, ReaderSettingsHandler, DatabaseExtractListener,
    ThumbRecyclerViewAdapter.ThumbInteractionListener {
    private var mVisible: Boolean = false
    private var switchLayoutJob: Job? = null

    var currentScaleType = ScaleType.FitPage
        private set
    var archive: Archive? = null
        private set
    private var currentPage = 0
    private var jumpPage = -1
    private var rtol = false
    private var volControl = false
    private var optionsMenu: Menu? = null
    private lateinit var imagePager: ViewPager2
    private lateinit var webtoonRecycler: WebtoonRecyclerView
    private lateinit var webtoonLayout: FrameLayout
    private lateinit var pageSeekBar: SeekBar
    private lateinit var pageSeekLayout: LinearLayout
    private lateinit var progressEndText: TextView
    private lateinit var progressStartText: TextView
    private val pageFragments = mutableListOf<PageFragment>()
    private var autoHideDelay = AUTO_HIDE_DELAY_MILLIS
    private var autoHideEnabled = true
    private var updateProgressJob: Job? = null
    private var dualPageEnabled = false
    private var isWebtoon = false
    private var isBookmarked = false

    private val dualPageAdapter by lazy { ReaderMultiFragmentAdapter() }
    private val pageAdapter by lazy { ReaderFragmentAdapter() }
    private val webtoonAdapter by lazy { WebtoonAdapter() }

    private val currentAdapter
        get() = (if (isWebtoon) webtoonRecycler.adapter else imagePager.adapter) as? IReaderAdapter
    private val useDoublePage
        get() = dualPageEnabled && !isWebtoon && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private val subtitle: String
        get() {
            return archive.let {
                if (it != null && it.numPages > 0) {
                    if (!useDoublePage || currentAdapter?.run { isSinglePage(getPositionFromPage(currentPage)) } == true)
                        getString(R.string.single_page_display, currentPage + 1, it.numPages)
                    else
                        getString(R.string.dual_page_display, currentPage + 1, currentPage + 2, it.numPages)
                }
                else
                    getString(R.string.no_page_display, currentPage + (if (useDoublePage) 2 else 1))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        launch { DualPageHelper.trashMergedPages(cacheDir) }

        setContentView(R.layout.activity_reader)
        val appBar: Toolbar = findViewById(R.id.reader_toolbar)
        setSupportActionBar(appBar)
        supportActionBar?.run {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_arrow_back_white_24dp)
            title = ""
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        pageSeekLayout = findViewById(R.id.page_seek_layout)
        ViewCompat.setOnApplyWindowInsetsListener(appBar) { _, insets ->
            var params = FrameLayout.LayoutParams(appBar.layoutParams)
            var safeTop = insets.displayCutout?.safeInsetTop ?: 0
            val systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            safeTop = if (safeTop > 0) safeTop else systemInsets.top
            var safeBottom = insets.displayCutout?.safeInsetBottom ?: 0
            safeBottom = if (safeBottom > 0) safeBottom else systemInsets.bottom
            val safeRight = insets.displayCutout?.safeInsetRight ?: systemInsets.right
            val safeLeft = insets.displayCutout?.safeInsetLeft

            val safeInsetLeft = safeLeft ?: systemInsets.left
            params.setMargins(safeInsetLeft, safeTop, safeRight, params.bottomMargin)
            appBar.layoutParams = params

            params = pageSeekLayout.layoutParams as FrameLayout.LayoutParams
            with(resources) {
                val margin = getDimensionPixelSize(R.dimen.seek_bar_margin)
                val bottomMargin = getDimensionPixelSize(R.dimen.seek_bar_bottom_margin)
                params.setMargins(safeInsetLeft + margin, params.topMargin, safeRight + margin, safeBottom + bottomMargin)
                pageSeekLayout.layoutParams = params
            }

            val bookmarkView: LinearLayout = findViewById(R.id.bookmark_list_layout)
            params = FrameLayout.LayoutParams(bookmarkView.layoutParams)
            params.setMargins(safeLeft ?: params.leftMargin, safeTop, params.rightMargin, safeBottom)
            bookmarkView.layoutParams = params

            insets
        }

        rtol = prefs.getBoolean(getString(R.string.rtol_pref_key), false)
        volControl = prefs.getBoolean(getString(R.string.vol_key_pref_key), false)
        autoHideDelay = truncate(prefs.castStringPrefToFloat(getString(R.string.fullscreen_timeout_key), AUTO_HIDE_DELAY_S) * 1000).toLong()
        autoHideEnabled = autoHideDelay >= 0
        dualPageEnabled = prefs.getBoolean(getString(R.string.dualpage_key), false)

        mVisible = true

        pageSeekBar = findViewById(R.id.page_seek_bar)
        pageSeekLayout.setBackgroundColor(MaterialColors.getColor(pageSeekLayout,
            R.attr.cardBackgroundColor
        ))
        progressStartText = findViewById(R.id.txt_progress_start)
        imagePager = findViewById(R.id.image_pager)

        val bundle = intent.extras
        val arcid = bundle?.getString(ID_STRING) ?: savedInstanceState?.getString(ID_STRING) ?: return
        val savedPage = when {
            savedInstanceState?.containsKey(CURRENT_PAGE_ID) == true -> savedInstanceState.getInt(
                CURRENT_PAGE_ID
            )
            bundle?.containsKey(PAGE_ID) == true -> bundle.getInt(PAGE_ID)
            else -> null
        }

        pageSeekLayout.visibility = View.GONE
        pageSeekBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            private var seekPage = -1
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                seekPage = currentAdapter?.getPositionFromPage(progress) ?: 0
                currentAdapter?.run { progressStartText.text = (getPageFromPosition(seekPage) + 1).toString() }
            }
            override fun onStartTrackingTouch(p0: SeekBar?) { switchLayoutJob?.cancel() }
            override fun onStopTrackingTouch(p0: SeekBar?) {
                if (seekPage > -1)
                    jumpToPage(seekPage)
                seekPage = -1
                delayedHide(autoHideDelay)
            }
        })

        val savedType = savedInstanceState?.getInt(SCALE_TYPE, -1) ?: -1
        if (savedType != -1) {
            currentScaleType = ScaleType.fromInt(savedType)
            isWebtoon = currentScaleType == ScaleType.Webtoon
            initializePager(appBar)
        }

        progressEndText = findViewById(R.id.txt_progress_end)
        launch {
            archive = DatabaseReader.getArchive(arcid)
            archive?.let {
                supportActionBar?.title = it.title

                if (savedType == -1) {
                    val bookmarkScaleType = ReaderTabHolder.getTab(it.id)?.scaleType
                    currentScaleType = when {
                        bookmarkScaleType != null -> bookmarkScaleType
                        it.isWebtoon -> ScaleType.Webtoon
                        else -> getScaleTypePref(prefs, resources)
                    }
                    isWebtoon = currentScaleType == ScaleType.Webtoon
                    initializePager(appBar)
                }

                if (savedPage != it.currentPage && ReaderTabHolder.isTabbed(it.id))
                    launch { WebHandler.updateProgress(it.id, currentPage) }

                //Use the page from the thumbnail over the bookmark
                val page = max(savedPage ?: it.currentPage, 0)
                it.currentPage = page
                currentPage = page
                jumpPage = currentPage
                supportActionBar?.subtitle = subtitle
                setTabbedIcon(ReaderTabHolder.isTabbed(it.id))

                if (it.numPages > 0) {
                    pageSeekBar.max = it.numPages - 1
                    progressEndText.text = it.numPages.toString()
                    if (mVisible)
                        pageSeekLayout.visibility = View.VISIBLE
                }
                pageSeekBar.progress = if (currentAdapter?.isSinglePage(page) == false) currentPage + 1 else currentPage
                progressStartText.text = if (currentAdapter?.isSinglePage(page) != false) (currentPage + 1).toString() else (currentPage + 2).toString()

                val adjustedPage = getAdjustedPage(page)
                currentAdapter?.loadImage(adjustedPage)
                jumpToPage(currentAdapter?.getPositionFromPage(adjustedPage) ?: adjustedPage)

                if (intent.getBooleanExtra(FORCE_REFRESH, false)) {
                    intent.removeExtra(FORCE_REFRESH)
                    it.extract(this@ReaderActivity, true)
                }

                for (listener in pageFragments)
                    listener.onArchiveLoad(it)
            }
        }
    }

    fun registerPage(listener: PageFragment) = pageFragments.add(listener)

    fun unregisterPage(listener: PageFragment) = pageFragments.remove(listener)

    private fun initializePager(appBar: Toolbar) {
        if (!isWebtoon) {
            imagePager.adapter = if (useDoublePage) dualPageAdapter else pageAdapter
            imagePager.offscreenPageLimit = 1
            imagePager.registerOnPageChangeCallback(object: ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(page: Int) = onPageChanged(page)
            } )
            imagePager.layoutDirection = if (rtol) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        } else {
            webtoonRecycler = findViewById(R.id.webtoon_recycler)
            val readerLayout: FrameLayout = findViewById(R.id.reader_frame_layout)
            with (readerLayout) {
                visibility = View.GONE
                removeView(appBar)
                removeView(pageSeekLayout)
            }
            with(webtoonRecycler) {
                layoutManager = LinearLayoutManager(this@ReaderActivity)
                adapter = webtoonAdapter
                isFocusable = false
                itemAnimator = null
                tapListener = ::onFragmentTap
                pageChangeListener = ::onPageChanged
                longPressListener = { onFragmentLongPress() }
            }

            webtoonLayout = findViewById(R.id.webtoon_layout)
            with(webtoonLayout) {
                visibility = View.VISIBLE
                addView(appBar)
                addView(pageSeekLayout)
            }
        }
    }

    private fun getScaleTypePref(context: Context) : ScaleType {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return getScaleTypePref(prefs, context.resources)
    }

    private fun getScaleTypePref(prefs: SharedPreferences, resources: Resources) : ScaleType {
        val scaleTypeString = prefs.getString(resources.getString(R.string.scale_type_pref), null)
        return ScaleType.fromString(scaleTypeString, resources)
    }

    private fun jumpToPage(page: Int) {
        if (isWebtoon)
            webtoonRecycler.scrollToPosition(page)
        else
            imagePager.setCurrentItem(page, false)
    }

    private fun onPageChanged(page: Int) {
        currentPage = currentAdapter?.run { getPageFromPosition(getAdjustedPage(page)) } ?: 0
        archive?.run {
            val current = this@ReaderActivity.currentPage
            launch(Dispatchers.IO) {
                if (ReaderTabHolder.updatePageIfTabbed(id, current)) {
                    updateProgressJob?.cancel()
                    clearNewFlag()
                    updateProgressJob = launch {
                        delay(PROGRESS_UPDATE_DELAY)
                        WebHandler.updateProgress(id, current)
                    }
                }
            }
            val markCompletePage = floor(numPages * 0.9f).toInt()
            if (numPages > 0 && current + 1 == markCompletePage && isNew)
                launch { clearNewFlag() }
        }

        pageSeekBar.progress = if (currentAdapter?.isSinglePage(page) == false) currentPage + 1 else currentPage
        progressStartText.text = if (currentAdapter?.isSinglePage(page) != false) (currentPage + 1).toString() else (currentPage + 2).toString()
        launch { currentAdapter?.loadImage(currentPage) }
        supportActionBar?.subtitle = subtitle
        if (currentAdapter?.containsPage(jumpPage, page) != true)
            jumpPage = -1
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val currentPage = currentAdapter?.getPageFromPosition(imagePager.currentItem) ?: 0
        super.onConfigurationChanged(newConfig)
        if (isWebtoon)
            webtoonRecycler.adapter?.run { notifyItemRangeChanged(0, itemCount) }
        else if (dualPageEnabled) {
            val adapter = when (newConfig.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> dualPageAdapter
                else -> pageAdapter
            }

            adapter.loadImage(currentPage, false)
            imagePager.adapter = adapter

            val position = currentAdapter?.getPositionFromPage(currentPage) ?: 0
            imagePager.setCurrentItem(position, false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        with(outState) {
            putInt(CURRENT_PAGE_ID, currentPage)
            putString(ID_STRING, archive?.id)
            putInt(SCALE_TYPE, currentScaleType.value)
        }
    }

    override fun handleTabSwipeLeft(tab: ReaderTab, position: Int) {
        setResult(Activity.RESULT_OK)
        super.handleTabSwipeLeft(tab, position)
        finish()
    }

    override fun setupReaderTabAdapter(adapter: ReaderTabViewAdapter) {
        val viewModel: ReaderTabViewModel by viewModels()
        viewModel.monitor { adapter.submitData(it) }
    }

    override fun onStart() {
        super.onStart()
        ReaderTabHolder.registerRemoveListener(this)
        ReaderTabHolder.registerClearListener(this)
        DatabaseReader.registerExtractListener(this)
    }

    override fun onTabRemoved(id: String) {
        if (id == archive?.id) {
            launch { setTabbedIcon(false) }
            archive?.currentPage = -1
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!volControl)
            return super.onKeyDown(keyCode, event)

        val pageAdjustment = if (rtol && !isWebtoon) -1 else 1
        return when(keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                jumpToPage(imagePager.currentItem + pageAdjustment)
                true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                jumpToPage(imagePager.currentItem - pageAdjustment)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onTabsCleared() {
        setTabbedIcon(false)
        archive?.currentPage = -1
    }

    private fun setTabbedIcon(tabbed: Boolean) = setTabbedIcon(optionsMenu?.findItem(R.id.bookmark_archive), tabbed)

    private fun getAdjustedPage(page: Int) = page

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        // Trigger the initial hide() shortly after the activity has been
        // created, to briefly hint to the user that UI controls
        // are available.
        if (autoHideEnabled)
            delayedHide(100)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.reader_menu, menu)
        optionsMenu = menu

        if (currentAdapter?.run { isSinglePage(getPositionFromPage(currentPage)) } == false) {
            with(menu) {
                findItem(R.id.swap_merged_page)?.isVisible = true
                findItem(R.id.split_merged_page)?.isVisible = true
            }
        }

        setTabbedIcon(menu.findItem(R.id.bookmark_archive), isBookmarked)
        return super.onCreateOptionsMenu(menu)
    }

    private fun setTabbedIcon(menuItem: MenuItem?, tabbed: Boolean) {
        isBookmarked = tabbed
        val icon = if (tabbed)
            R.drawable.ic_bookmark_white_24dp
        else
            R.drawable.ic_bookmark_border_white_24dp
        menuItem?.icon = ContextCompat.getDrawable(this, icon)

    }

    override fun onStop() {
        super.onStop()
        Glide.get(this).clearMemory()
        ReaderTabHolder.unregisterRemoveListener(this)
        ReaderTabHolder.unregisterAddListener(this)
        DatabaseReader.unregisterExtractListener(this)
    }

    override fun updateScaleType(type: ScaleType) {
        if (type != currentScaleType) {
            archive?.run { launch { ReaderTabHolder.updateScaleTypeIfTabbed(id, type) } }
            if (type == ScaleType.Webtoon || currentScaleType == ScaleType.Webtoon) {
                currentScaleType = type
                recreate()
            } else {
                currentScaleType = type
                for (listener in pageFragments)
                    listener.onScaleTypeChange(type)
            }
        }
    }

    override fun startDetailsActivity(id: String) {
        val intent = Intent(this, ArchiveDetails::class.java)
        val bundle = Bundle().apply {
            putString("id", id)
            if (id == archive?.id)
                putInt(FROM_READER_PAGE, currentPage)
        }
        intent.putExtras(bundle)
        addIntentFlags(intent, id)
        startActivity(intent)
    }

    override fun handleButton(buttonId: Int) {
        when (buttonId) {
            R.id.detail_button -> {
                archive?.let {
                    setResult(Activity.RESULT_OK)
                    startDetailsActivity(it.id)
                    finish()
                }
            }
            R.id.goto_button -> {
                archive?.let {
                    if (it.numPages >= 0) {
                        val dialog = GalleryPreviewDialogFragment.createInstance(it.id, currentPage)
                        dialog.show(supportFragmentManager, "page_picker")
                    }
                }
            }
            R.id.thumb_button -> archive?.let {
                launch {
                    DatabaseReader.refreshThumbnail(it.id, this@ReaderActivity, currentPage)
                    Toast.makeText(this@ReaderActivity, getString(R.string.update_thumbnail_message), Toast.LENGTH_SHORT).show()
                }
            }
            R.id.random_archive_button -> {
                launch {
                    DatabaseReader.getRandomArchive().let {
                        startReaderActivity(it.id)
                        finish()
                    }
                }
            }
            R.id.refresh_button -> {
                closeSettings()
                archive?.let {
                    it.invalidateCache()
                    launch {
                        with(Glide.get(this@ReaderActivity)) {
                            clearMemory()
                            withContext(Dispatchers.IO) { clearDiskCache() }
                        }
                        DualPageHelper.clearMergedPages(cacheDir)
                        intent.putExtra(FORCE_REFRESH, true)
                        intent.putExtra(PAGE_ID, currentPage)
                        finish()
                        startActivity(intent)
                    }
                }
            }
            R.id.bookmark_button -> {
                closeSettings()
                drawerLayout.openDrawer(navView)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.bookmark_archive -> {
                archive?.let {
                    launch {
                        if (!ReaderTabHolder.isTabbed(it.id)) {
                            val defaultScaleType = getScaleTypePref(this@ReaderActivity)
                            val scaleType = if (currentScaleType != defaultScaleType) currentScaleType else null
                            ReaderTabHolder.addTab(it, currentPage, scaleType)
                            setTabbedIcon(item, true)
                        } else {
                            ReaderTabHolder.removeTab(it.id)
                            setTabbedIcon(item, false)
                        }
                    }
                    return true
                }
            }
            R.id.open_settings -> openSettings()
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onThumbSelection(page: Int) {
        closeSettings()
        val adjustedPage = getAdjustedPage(page)
        currentAdapter?.loadImage(adjustedPage)
        jumpToPage(currentAdapter?.getPositionFromPage(adjustedPage) ?: adjustedPage)
        currentPage = page
    }

    override fun onThumbLongPress(page: Int) = false

    override fun onTabInteraction(tab: ReaderTab) {
        if (tab.id != archive?.id) {
            setResult(Activity.RESULT_OK)
            super.onTabInteraction(tab)
            finish()
        } else
            drawerLayout.closeDrawers()
    }

    private fun toggle() {
        if (mVisible) {
            hide()
        } else {
            show()
        }
    }

    private fun hide() {
        // Hide UI first
        supportActionBar?.hide()
        pageSeekLayout.visibility = View.GONE
        mVisible = false

        switchLayoutJob?.cancel()
        switchLayoutJob = launch {
            delay(UI_ANIMATION_DELAY)

            with(WindowInsetsControllerCompat(window, imagePager)) {
               hide(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.statusBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun show() {
        // Show the system bar
        with(WindowInsetsControllerCompat(window, imagePager)) {
            show(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.statusBars())
        }

        mVisible = true

        switchLayoutJob?.cancel()
        switchLayoutJob = launch {
            delay(UI_ANIMATION_DELAY)
            supportActionBar?.show()
            if (pageSeekBar.max > 0)
                pageSeekLayout.visibility = View.VISIBLE

            if (autoHideEnabled)
                delayedHide(autoHideDelay)
        }
    }

    /**
     * Schedules a call to hide() in [delayMillis], canceling any
     * previously scheduled calls.
     */
    private fun delayedHide(delayMillis: Long) {
        switchLayoutJob?.cancel()
        switchLayoutJob = launch {
            delay(delayMillis)
            hide()
        }
    }

    override fun onFragmentTap(zone: TouchZone) {
        val currentPosition = if (isWebtoon) webtoonRecycler.firstVisibleItemPosition else imagePager.currentItem
        when (zone) {
            TouchZone.Center -> toggle()
            TouchZone.Left -> {
                val next = if (rtol && !isWebtoon) currentPosition + 1 else currentPosition - 1
                jumpToPage(next)
            }
            TouchZone.Right -> {
                val next = if (rtol && !isWebtoon) currentPosition - 1 else currentPosition + 1
                jumpToPage(next)
            }
        }
    }

    private fun openSettings() {
        if (!supportFragmentManager.isDestroyed) {
            val settingsFragment = ReaderSettingsDialogFragment.newInstance(currentScaleType)
            settingsFragment.show(supportFragmentManager, "reader_settings")
        }
    }

    private fun closeSettings() {
        val settingsFragment = supportFragmentManager.findFragmentByTag("reader_settings") as? ReaderSettingsDialogFragment
        settingsFragment?.dismiss()
    }

    override fun onFragmentLongPress() : Boolean {
        openSettings()
        return true
    }

    override fun onClose() {
        hide()
    }

    override fun onResume() {
        super.onResume()
        if (autoHideEnabled)
            delayedHide(100)
    }

    override fun onDestroy() {
        super.onDestroy()
        archive?.run {
            val cacheDir = cacheDir
            GlobalScope.launch { DualPageHelper.trashMergedPages(cacheDir, id) }
        }
    }

    override fun onImageLoadError() { toggle() }

    fun onMergeFailed(page: Int, failPage: Int, split: Boolean) {
        if (isWebtoon)
            return

        if (dualPageAdapter.onMergeFailed(page, failPage, split)) {
            supportActionBar?.subtitle = subtitle
            pageSeekBar.progress = currentPage
            progressStartText.text = (currentPage + 1).toString()
            optionsMenu?.run {
                findItem(R.id.swap_merged_page)?.isVisible = false
                findItem(R.id.split_merged_page)?.isVisible = false
            }
        }

        if (jumpPage >= 0 && dualPageAdapter.getPositionFromPage(jumpPage) != imagePager.currentItem)
            imagePager.setCurrentItem(dualPageAdapter.getPositionFromPage(jumpPage), false)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            imagePager.offscreenPageLimit = ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
    }

    override fun onExtract(id: String, pageCount: Int) {
        if (id != archive?.id || archive?.numPages == currentAdapter?.getItemCount())
            return

        launch {
            withContext(Dispatchers.Main) {
                pageSeekBar.max = pageCount - 1
                progressEndText.text = pageCount.toString()
            }

            pageAdapter.updateLoadedPages(pageCount)
            if (dualPageEnabled)
                dualPageAdapter.updateLoadedPages(pageCount)
        }
    }

    companion object {
        private const val AUTO_HIDE_DELAY_S = 5f
        /**
         * If [autoHideEnabled] is set, the number of milliseconds to wait after
         * user interaction before hiding the system UI.
         */
        private const val AUTO_HIDE_DELAY_MILLIS = AUTO_HIDE_DELAY_S.toLong() * 1000

        /**
         * Some older devices needs a small delay between UI widget updates
         * and a change of the status and navigation bar.
         */
        private const val UI_ANIMATION_DELAY = 300L

        private const val SCALE_TYPE = "scale_type"
    }

    private inner class ReaderMultiFragmentAdapter : ReaderAdapter<UInt>() {
        override val defaultPageSize = 2u

        override fun createFragment(position: Int): Fragment {
            val page = getPageFromPosition(getAdjustedPage(position))
            return if (loadedPages[position] > 1u)
                ReaderMultiPageFragment.createInstance(page, page + 1, archive?.id)
            else
                ReaderFragment.createInstance(page)
        }

        override fun getItemId(position: Int): Long {
            return getPageFromPosition(position).toLong()
        }

        override fun containsItem(itemId: Long): Boolean {
            val position = getPositionFromPage(itemId.toInt())
            return position >= 0 && position < loadedPages.size
        }

        override fun containsPage(page: Int, position: Int) = getPositionFromPage(page) == position

        override fun getItemCount(): Int = loadedPages.size

        override fun isPageLoaded(page: Int) = loadedPages[page] != 0u

        fun onMergeFailed(page: Int, failPage: Int, split: Boolean): Boolean {
            val adapterPage = getPositionFromPage(page)
            if (page < archive!!.numPages - 1) {
                loadedPages[adapterPage] = 1u
                notifyItemChanged(adapterPage)

                if (split) {
                    loadedPages.add(adapterPage + 1, 2u)
                    notifyItemInserted(adapterPage + 1)
                    if (loadedPages.last() == 2u) {
                        --loadedPages[loadedPages.lastIndex]
                        notifyItemChanged(loadedPages.lastIndex)
                    } else {
                        loadedPages.removeLast()
                        notifyItemRemoved(loadedPages.size)
                    }
                } else if (page != failPage && adapterPage < loadedPages.lastIndex) {
                    loadedPages.add(adapterPage + 1, 1u)
                    notifyItemInserted(adapterPage + 1)
                } else if (loadedPages.size < archive!!.numPages) {
                    val last = loadedPages.lastIndex
                    if (loadedPages[last] == 1u && adapterPage != last) {
                        ++loadedPages[last]
                        notifyItemChanged(last)
                    } else {
                        loadedPages.add(1u)
                        notifyItemInserted(loadedPages.lastIndex)
                    }
                }
            } else {
                loadedPages[adapterPage] = 1u
                notifyItemChanged(adapterPage)
            }

            return adapterPage == getPositionFromPage(currentPage)
        }

        override fun adjustLoadedPages(page: Int): Int {
            val adapterPage = getPositionFromPage(page)
            if (adapterPage >= loadedPages.size) {
                val currentSize = loadedPages.size
                for (i in currentSize..adapterPage)
                    loadedPages.add(defaultPageSize)
                return loadedPages.size - currentSize
            } else {
                archive?.let {
                    for (i in loadedPages.size until it.numPages / 2)
                        loadedPages.add(defaultPageSize)
                }
            }
            return 0
        }

        override fun getPositionFromPage(page: Int): Int {
            var pageSum = 0u
            for (i in 0 until page) {
                pageSum += if (i < loadedPages.size) loadedPages[i] else defaultPageSize
                if (pageSum > page.toUInt())
                    return i
            }
            return page
        }

        override fun getPageFromPosition(position: Int): Int {
            if (position >= loadedPages.size)
                return -1

            var pageSum = 0u
            for (i in 0 until position)
                pageSum += loadedPages[i]
            return pageSum.toInt()
        }

        override fun isSinglePage(position: Int) = position >= 0 && position < loadedPages.size && loadedPages[position] == 1u

        @SuppressLint("NotifyDataSetChanged")
        override fun updateLoadedPages(pageCount: Int) {
            val count = ceil(pageCount / 2f).toInt()
            loadedPages.clear()
            for (i in 0 until count)
                loadedPages.add(defaultPageSize)
            notifyDataSetChanged()
        }
    }

    private interface IReaderAdapter {
        val defaultPageSize: UInt
        fun isSinglePage(position: Int): Boolean
        fun clearPages()
        fun updateLoadedPages(pageCount: Int)
        fun adjustLoadedPages(page: Int): Int
        fun getPositionFromPage(page: Int): Int
        fun getPageFromPosition(position: Int): Int
        fun containsPage(page: Int, position: Int): Boolean
        fun loadImage(page: Int, preload: Boolean = true)
        fun getItemCount(): Int
    }

    private inner class WebtoonAdapter : RecyclerView.Adapter<WebtoonReaderViewHolder>(),
        IReaderAdapter {
        private val loadedPages = mutableListOf<Boolean>()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WebtoonReaderViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.fragment_reader, parent, false)
            return WebtoonReaderViewHolder(parent.context, view, this@ReaderActivity)
        }

        override fun onBindViewHolder(holder: WebtoonReaderViewHolder, position: Int) {
            holder.onAttach(position)
        }

        override fun onViewRecycled(holder: WebtoonReaderViewHolder) {
            holder.onDetach()
            super.onViewRecycled(holder)
        }

        override val defaultPageSize = 1u
        override fun isSinglePage(position: Int) = true

        override fun clearPages() {
            val size = loadedPages.size
            loadedPages.clear()
            notifyItemRangeRemoved(0, size)
        }

        override fun loadImage(page: Int, preload: Boolean) {
            if (archive?.hasPage(page) == false) {
                if (archive?.numPages == 0) {
                    imagePager.visibility = View.INVISIBLE
                    webtoonRecycler.visibility = View.GONE
                }
                return
            }
            imagePager.visibility = View.VISIBLE

            val addedPages = adjustLoadedPages(page)
            if (addedPages > 0)
                notifyItemRangeInserted(loadedPages.size - addedPages, addedPages)

            if (preload) {
                for (i in (defaultPageSize.toInt() * -1..defaultPageSize.toInt())) {
                    val newPage = page + i
                    if (newPage >= 0 && (newPage >= loadedPages.size || !isPageLoaded(page + i)))
                        loadImage(page + i, false)
                }
            }
        }

        private fun isPageLoaded(page: Int) = loadedPages[page]
        override fun getItemCount(): Int = loadedPages.size
        override fun getPositionFromPage(page: Int) = page
        override fun getPageFromPosition(position: Int) = position
        override fun containsPage(page: Int, position: Int) = page == position

        @SuppressLint("NotifyDataSetChanged")
        override fun updateLoadedPages(pageCount: Int) {
            loadedPages.clear()
            for (i in 0 until pageCount)
                loadedPages.add(true)
            notifyDataSetChanged()
        }

        override fun adjustLoadedPages(page: Int) : Int {
            var addedPages = 0
            when {
                archive?.run { numPages > 0 && loadedPages.size != numPages } == true -> {
                    val currentSize = loadedPages.size
                    for (i in currentSize until archive!!.numPages)
                        loadedPages.add(true)
                    loadedPages.fill(true)
                    return loadedPages.size - currentSize
                }
                page >= loadedPages.size -> {
                    val currentSize = loadedPages.size
                    for (i in currentSize..page)
                        loadedPages.add(false)
                    addedPages = loadedPages.size - currentSize
                }
                loadedPages[page] -> return 0
            }
            loadedPages[page] = true
            return addedPages
        }
    }

    private abstract inner class ReaderAdapter<T> : FragmentStateAdapter(this), IReaderAdapter {
        protected val loadedPages = mutableListOf<T>()
        abstract override val defaultPageSize: UInt

        override fun isSinglePage(position: Int) = true
        override fun clearPages() {
            val size = loadedPages.size
            loadedPages.clear()
            notifyItemRangeRemoved(0, size)
        }
        abstract override fun updateLoadedPages(pageCount: Int)
        abstract override fun adjustLoadedPages(page: Int): Int
        abstract override fun getPositionFromPage(page: Int): Int
        abstract override fun getPageFromPosition(position: Int): Int
        abstract override fun containsPage(page: Int, position: Int): Boolean
        protected abstract fun isPageLoaded(page: Int): Boolean

        override fun loadImage(page: Int, preload: Boolean) {
            if (archive?.hasPage(page) == false) {
                if (archive?.numPages == 0) {
                    imagePager.visibility = View.INVISIBLE
                }
                return
            }
            imagePager.visibility = View.VISIBLE

            val addedPages = adjustLoadedPages(page)
            if (addedPages > 0)
                notifyItemRangeInserted(loadedPages.size - addedPages, addedPages)

            if (preload) {
                for (i in (defaultPageSize.toInt() * -1..defaultPageSize.toInt())) {
                    val newPage = page + i
                    if (newPage >= 0 && (newPage >= loadedPages.size || !isPageLoaded(page + i)))
                        loadImage(page + i, false)
                }
            }
        }

    }

    private open inner class ReaderFragmentAdapter : ReaderAdapter<Boolean>() {
        override val defaultPageSize = 1u

        override fun createFragment(position: Int): Fragment =
            ReaderFragment.createInstance(getAdjustedPage(position))
        override fun getItemCount(): Int = loadedPages.size
        override fun isPageLoaded(page: Int) = loadedPages[page]
        override fun getPositionFromPage(page: Int) = page
        override fun getPageFromPosition(position: Int) = position
        override fun containsPage(page: Int, position: Int) = page == position

        @SuppressLint("NotifyDataSetChanged")
        override fun updateLoadedPages(pageCount: Int) {
            loadedPages.clear()
            for (i in 0 until pageCount)
                loadedPages.add(true)
            notifyDataSetChanged()
        }

        override fun adjustLoadedPages(page: Int) : Int {
            var addedPages = 0
            when {
                archive?.run { numPages > 0 && loadedPages.size != numPages } == true -> {
                    val currentSize = loadedPages.size
                    for (i in currentSize until archive!!.numPages)
                        loadedPages.add(true)
                    loadedPages.fill(true)
                    return loadedPages.size - currentSize
                }
                page >= loadedPages.size -> {
                    val currentSize = loadedPages.size
                    for (i in currentSize..page)
                        loadedPages.add(false)
                    addedPages = loadedPages.size - currentSize
                }
                loadedPages[page] -> return 0
            }
            loadedPages[page] = true
            return addedPages
        }
    }
}

/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2021 Utazukin
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
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProviders
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.utazukin.ichaival.ReaderFragment.OnFragmentInteractionListener
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

private const val ID_STRING = "id"
private const val PAGE_ID = "page"
private const val CURRENT_PAGE_ID = "currentPage"
private const val PROGRESS_UPDATE_DELAY = 500L //ms
private const val DOUBLE_PAGE_OFFSET = 12345L

class ReaderActivity : BaseActivity(), OnFragmentInteractionListener, TabRemovedListener, TabsClearedListener, ReaderSettingsHandler, DatabaseExtractListener, ThumbRecyclerViewAdapter.ThumbInteractionListener {
    private var mVisible: Boolean = false
    private var switchLayoutJob: Job? = null

    var currentScaleType = ScaleType.FitPage
        private set
    var archive: Archive? = null
        private set
    private var currentPage = 0
    private var retryCount = 0
    private var rtol = false
    private var volControl = false
    private val loadedPages = mutableListOf<UInt>()
    private var optionsMenu: Menu? = null
    private lateinit var failedMessage: TextView
    private lateinit var imagePager: ViewPager2
    private val pageFragments = mutableListOf<PageFragment>()
    private var autoHideDelay = AUTO_HIDE_DELAY_MILLIS
    private var autoHideEnabled = true
    private val retrying = AtomicBoolean()
    private var updateProgressJob: Job? = null
    private var dualPageEnabled = false
    private val useDoublePage
        get() = dualPageEnabled && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val defaultPageSize
        get() = if (useDoublePage) 2u else 1u
    private val subtitle: String
        get() {
            return archive.let {
                if (it != null && it.numPages > 0) {
                    if (!useDoublePage || loadedPages[getAdapterPage(currentPage)] == 1u)
                        "Page ${currentPage + 1}/${it.numPages}"
                    else
                        "Pages ${currentPage + 1}-${currentPage + 2}/${it.numPages}"
                }
                else
                    "Page ${currentPage + (if (useDoublePage) 2 else 1)}"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        retryCount = 0
        setContentView(R.layout.activity_reader)
        val appBar: Toolbar = findViewById(R.id.reader_toolbar)
        setSupportActionBar(appBar)
        supportActionBar?.run {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_arrow_back_white_24dp)
            title = ""
        }
        currentScaleType = ScaleType.fromInt(savedInstanceState?.getInt(SCALE_TYPE, 0) ?: 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            window.setDecorFitsSystemWindows(false)

        ViewCompat.setOnApplyWindowInsetsListener(appBar) { _, insets ->
            var params = FrameLayout.LayoutParams(appBar.layoutParams)
            var safeTop = insets.displayCutout?.safeInsetTop ?: 0
            val systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            safeTop = if (safeTop > 0) safeTop else systemInsets.top
            val safeBottom = insets.displayCutout?.safeInsetBottom
            val safeRight = insets.displayCutout?.safeInsetRight ?: systemInsets.right
            val safeLeft = insets.displayCutout?.safeInsetLeft

            params.setMargins(params.leftMargin, safeTop, safeRight, params.bottomMargin)
            appBar.layoutParams = params

            val bookmarkView: LinearLayout = findViewById(R.id.bookmark_list_layout)
            params = FrameLayout.LayoutParams(bookmarkView.layoutParams)
            params.setMargins(safeLeft ?: params.leftMargin,
                safeTop, params.rightMargin, safeBottom ?: params.bottomMargin)
            bookmarkView.layoutParams = params

            insets
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        rtol = prefs.getBoolean(getString(R.string.rtol_pref_key), false)
        volControl = prefs.getBoolean(getString(R.string.vol_key_pref_key), false)
        autoHideDelay = truncate(prefs.castStringPrefToFloat(getString(R.string.fullscreen_timeout_key), AUTO_HIDE_DELAY_S) * 1000).toLong()
        autoHideEnabled = autoHideDelay >= 0
        dualPageEnabled = prefs.getBoolean(getString(R.string.dualpage_key), false)

        mVisible = true

        imagePager = findViewById(R.id.image_pager)
        imagePager.adapter = ReaderFragmentAdapter()
        imagePager.offscreenPageLimit = 1
        imagePager.registerOnPageChangeCallback(object: ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(page: Int) {
                currentPage = if (useDoublePage) getPageFromPosition(getAdjustedPage(page)) else page
                archive?.let {
                    launch(Dispatchers.IO) {
                        if (ReaderTabHolder.updatePageIfTabbed(it.id, currentPage)) {
                            updateProgressJob?.cancel()
                            DatabaseReader.setArchiveNewFlag(it.id)
                            updateProgressJob = launch {
                                delay(PROGRESS_UPDATE_DELAY)
                                WebHandler.updateProgress(it.id, currentPage)
                            }
                        }
                    }
                    val markCompletePage = floor(it.numPages * 0.9f).toInt()
                    if (it.numPages > 0 && currentPage + 1 == markCompletePage)
                        launch(Dispatchers.IO) { DatabaseReader.setArchiveNewFlag(it.id) }
                }

                loadImage(currentPage)
                supportActionBar?.subtitle = subtitle
            }

        } )
        imagePager.layoutDirection = if (rtol) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

        failedMessage = findViewById(R.id.failed_message)
        failedMessage.setOnClickListener { toggle() }

        val bundle = intent.extras
        val arcid = bundle?.getString(ID_STRING) ?: savedInstanceState?.getString(ID_STRING) ?: return
        val savedPage = when {
            savedInstanceState?.containsKey(CURRENT_PAGE_ID) == true -> savedInstanceState.getInt(CURRENT_PAGE_ID)
            bundle?.containsKey(PAGE_ID) == true -> bundle.getInt(PAGE_ID)
            else -> null
        }

        launch {
            archive = withContext(Dispatchers.IO) { DatabaseReader.getArchive(arcid) }
            archive?.let {
                supportActionBar?.title = it.title

                if (savedPage != null && savedPage != it.currentPage && ReaderTabHolder.isTabbed(it.id))
                    launch(Dispatchers.IO) { WebHandler.updateProgress(it.id, currentPage) }

                //Use the page from the thumbnail over the bookmark
                val page = savedPage ?: max(it.currentPage, 0)
                it.currentPage = page
                currentPage = page
                supportActionBar?.subtitle = subtitle
                setTabbedIcon(ReaderTabHolder.isTabbed(it.id))

                val adjustedPage = getAdjustedPage(page)
                loadImage(adjustedPage)
                imagePager.setCurrentItem(getAdapterPage(adjustedPage), false)

                for (listener in pageFragments)
                    listener.onArchiveLoad(it)
            }
        }
    }

    fun registerPage(listener: PageFragment) = pageFragments.add(listener)

    fun unregisterPage(listener: PageFragment) = pageFragments.remove(listener)

    override fun onConfigurationChanged(newConfig: Configuration) {
        val currentPage = getPageFromPosition(imagePager.currentItem)
        if (dualPageEnabled) {
            val oldSize = loadedPages.size
            when (newConfig.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> {
                    val start = loadedPages.size / 2
                    loadedPages.removeRange(start, start)
                    imagePager.adapter?.notifyItemRangeRemoved(start, start)
                    for (i in 0 until loadedPages.size) {
                        if (loadedPages[i] == 1u)
                            loadedPages[i] = 2u
                    }
                }
                else -> {
                    while (loadedPages.size < archive!!.numPages)
                        loadedPages.add(1u)
                    imagePager.adapter?.notifyItemRangeInserted(oldSize - 1, archive!!.numPages - oldSize)
                    for (i in 0 until oldSize) {
                        if (loadedPages[i] == 2u)
                            loadedPages[i] = 1u
                    }
                }
            }
        }
        super.onConfigurationChanged(newConfig)
        if (dualPageEnabled) {
            val page = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) getAdapterPage(currentPage) else currentPage
            imagePager.setCurrentItem(page, false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(CURRENT_PAGE_ID, currentPage)
        outState.putString(PAGE_ID, archive?.id)
        outState.putInt(SCALE_TYPE, currentScaleType.value)
    }

    override fun onCreateDrawer() {
        drawerLayout = findViewById(R.id.drawer_layout)
        navView = drawerLayout.findViewById(R.id.nav_view)
        val tabView: RecyclerView = findViewById(R.id.tab_view)
        val listener = this
        val viewModel = ViewModelProviders.of(this).get(ReaderTabViewModel::class.java)
        with(tabView) {
            layoutManager = LinearLayoutManager(context)
            adapter = ReaderTabViewAdapter(listener, listener, Glide.with(listener)).also {
                viewModel.bookmarks.observe(this@ReaderActivity, { list -> it.submitList(list) })
            }

            val dividerDecoration = DividerItemDecoration(context, LinearLayoutManager.VERTICAL)
            addItemDecoration(dividerDecoration)
        }

        val closeButton: ImageView = findViewById(R.id.clear_bookmark)
        closeButton.setOnClickListener{ ReaderTabHolder.removeAll() }

        val touchHelper = BookmarkTouchHelper(this)
        touchHelper.leftSwipeListener = { id, _ ->
            setResult(Activity.RESULT_OK)
            startDetailsActivity(id)
            finish()
        }
        ItemTouchHelper(touchHelper).attachToRecyclerView(tabView)
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

        val pageAdjustment = if (rtol) -1 else 1
        return when(keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                imagePager.setCurrentItem(imagePager.currentItem + pageAdjustment, false)
                true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                imagePager.setCurrentItem(imagePager.currentItem - pageAdjustment, false)
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

    private fun adjustLoadedPages(page: Int) : Int {
        if (useDoublePage) {
            val adapterPage = getAdapterPage(page)
            if (adapterPage >= loadedPages.size) {
                val currentSize = loadedPages.size
                for (i in currentSize..adapterPage)
                    loadedPages.add(defaultPageSize)
                return loadedPages.size - currentSize
            }
            return 0
        }

        var addedPages = 0
        when {
            archive?.run { numPages > 0 && loadedPages.size != numPages } == true -> {
                val currentSize = loadedPages.size
                for (i in currentSize until archive!!.numPages)
                    loadedPages.add(defaultPageSize)
                for (i in 0 until loadedPages.size) {
                    if (loadedPages[i] != 1u)
                        loadedPages[i] = defaultPageSize
                }
                return loadedPages.size - currentSize
            }
            page >= loadedPages.size -> {
                val currentSize = loadedPages.size
                for (i in currentSize..page)
                    loadedPages.add(0u)
                addedPages = loadedPages.size - currentSize
            }
            loadedPages[page] > 0u -> return 0
        }
        loadedPages[page] = defaultPageSize
        return addedPages
    }

    private fun loadImage(page: Int, preload: Boolean = true) {
        if (archive?.hasPage(page) == false) {
            if (archive?.numPages == 0) {
                failedMessage.visibility = View.VISIBLE
                imagePager.visibility = View.INVISIBLE
            }
            return
        }
        imagePager.visibility = View.VISIBLE
        failedMessage.visibility = View.GONE

        val addedPages = adjustLoadedPages(page)
        if (addedPages > 0)
            imagePager.adapter?.notifyItemRangeInserted(loadedPages.size - addedPages, addedPages)

        if (preload) {
            for (i in (defaultPageSize.toInt() * -1..defaultPageSize.toInt())) {
                val newPage = page + i
                if (newPage >= 0 && (newPage >= loadedPages.size || loadedPages[page + i] == 0u))
                    loadImage(page + i, false)
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        // Trigger the initial hide() shortly after the activity has been
        // created, to briefly hint to the user that UI controls
        // are available.
        if (autoHideEnabled)
            delayedHide(100)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.reader_menu, menu)
        optionsMenu = menu
        archive?.let {
            val bookmarker = menu?.findItem(R.id.bookmark_archive)
            launch { setTabbedIcon(bookmarker, ReaderTabHolder.isTabbed(it.id)) }
        }
        return super.onCreateOptionsMenu(menu)
    }

    private fun setTabbedIcon(menuItem: MenuItem?, tabbed: Boolean) {
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
            currentScaleType = type
            for (listener in pageFragments)
                listener.onScaleTypeChange(type)
        }
    }

    override fun startDetailsActivity(id: String) {
        val intent = Intent(this, ArchiveDetails::class.java)
        val bundle = Bundle()
        bundle.putString("id", id)
        if (id == archive?.id)
            bundle.putInt(FROM_READER_PAGE, currentPage)
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
            R.id.random_archive_button -> {
                launch {
                    val randArchive = DatabaseReader.getRandomArchive()
                    if (randArchive != null) {
                        startReaderActivity(randArchive.id)
                        finish()
                    }
                }
            }
            R.id.refresh_button -> {
                retryCount = 0
                closeSettings()
                archive?.run {
                    invalidateCache()
                    launch {
                        loadedPages.clear()
                        withContext(Dispatchers.IO) { extract() }
                        loadImage(currentPage)
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
                            ReaderTabHolder.addTab(it, currentPage)
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
                onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onThumbSelection(page: Int) {
        closeSettings()
        val adjustedPage = getAdjustedPage(page)
        loadImage(adjustedPage)
        imagePager.setCurrentItem(getAdapterPage(adjustedPage), false)
        currentPage = page
    }

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

    @Suppress("DEPRECATION")
    private fun hide() {
        // Hide UI first
        supportActionBar?.hide()
        mVisible = false

        // Schedule a runnable to remove the status and navigation bar after a delay
        switchLayoutJob?.cancel()
        switchLayoutJob = launch {
            delay(UI_ANIMATION_DELAY)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                imagePager.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_LOW_PROFILE or
                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            } else {
                window.insetsController?.run {
                    hide(WindowInsets.Type.systemBars() or WindowInsets.Type.statusBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun show() {
        // Show the system bar
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            imagePager.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        } else
            window.insetsController?.show(WindowInsets.Type.systemBars() or WindowInsets.Type.statusBars())

        mVisible = true

        // Schedule a runnable to display UI elements after a delay
        switchLayoutJob?.cancel()
        switchLayoutJob = launch {
            delay(UI_ANIMATION_DELAY)
            supportActionBar?.show()

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
        when (zone) {
            TouchZone.Center -> toggle()
            TouchZone.Left -> {
                val next = if (rtol) imagePager.currentItem + 1 else imagePager.currentItem - 1
                imagePager.setCurrentItem(next, false)
            }
            TouchZone.Right -> {
                val next = if (rtol) imagePager.currentItem - 1 else imagePager.currentItem + 1
                imagePager.setCurrentItem(next, false)
            }
        }
    }

    private fun openSettings() {
        val settingsFragment = ReaderSettingsDialogFragment.newInstance(currentScaleType)
        settingsFragment.show(supportFragmentManager, "reader_settings")
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

    override fun onImageLoadError(fragment: PageFragment) : Boolean {
        return if (retryCount < 3) {
            archive?.run {
                if (retrying.compareAndSet(false, true)) {
                    invalidateCache()
                    ++retryCount
                    launch {
                        withContext(Dispatchers.IO) { extract() }
                        retrying.set(false)
                        fragment.reloadImage()
                    }
                } else
                    fragment.reloadImage()
            }

            true
        } else {
            failedMessage.visibility = View.VISIBLE
            imagePager.visibility = View.INVISIBLE
            false
        }
    }

    fun onMergeFailed(page: Int) {
        val adapterPage = getAdapterPage(page)
        loadedPages[adapterPage] = 1u
        loadedPages.add(adapterPage + 1, 2u)
        imagePager.adapter?.notifyItemInserted(adapterPage + 1)
    }

    private fun getAdapterPage(position: Int) : Int {
        var pageSum = 0u
        for (i in 0 until position) {
            pageSum += if (i < loadedPages.size) loadedPages[i] else 2u
            if (pageSum > position.toUInt())
                return i
        }
        return position
    }

    private fun getPageFromPosition(position: Int) : Int {
        if (position >= loadedPages.size)
            return -1

        var pageSum = 0u
        for (i in 0 until position)
            pageSum += loadedPages[i]
        return pageSum.toInt()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onExtract(id: String, pageCount: Int) {
        if (id != archive?.id || archive?.numPages == loadedPages.size)
            return

        launch {
            val count = if (useDoublePage) ceil(pageCount / 2f).toInt() else pageCount
            loadedPages.clear()
            for (i in 0 until count)
                loadedPages.add(defaultPageSize)

            imagePager.adapter?.notifyDataSetChanged()
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

    private inner class ReaderFragmentAdapter : FragmentStateAdapter(this) {
        override fun createFragment(position: Int) : Fragment {
            return if (useDoublePage) {
                val page = getPageFromPosition(getAdjustedPage(position))
                if (loadedPages[position] > 1u) {
                    ReaderMultiPageFragment.createInstance(page, page + 1)
                } else {
                    ReaderFragment.createInstance(page)
                }
            } else {
                ReaderFragment.createInstance(getAdjustedPage(position))
            }
        }

        override fun getItemId(position: Int): Long {
            return if (useDoublePage) getPageFromPosition(position).toLong() + DOUBLE_PAGE_OFFSET else super.getItemId(position)
        }

        override fun containsItem(itemId: Long): Boolean {
            val page = getPageFromPosition((itemId - DOUBLE_PAGE_OFFSET).toInt()).toLong()
            if (useDoublePage && page < 0)
                return false
            return if (useDoublePage) super.containsItem(page) else super.containsItem(itemId)
        }

        override fun getItemCount(): Int = loadedPages.size
    }
}

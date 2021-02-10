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

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.ViewModelProviders
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.bumptech.glide.Glide
import com.utazukin.ichaival.ReaderFragment.OnFragmentInteractionListener
import kotlinx.android.synthetic.main.activity_reader.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.truncate

private const val ID_STRING = "id"
private const val PAGE_ID = "page"
private const val CURRENT_PAGE_ID = "currentPage"

class ReaderActivity : BaseActivity(), OnFragmentInteractionListener, TabRemovedListener, TabsClearedListener, ReaderSettingsHandler, ThumbRecyclerViewAdapter.ThumbInteractionListener {
    private val mHideHandler = Handler()
    private val mHidePart2Runnable = Runnable {
        // Delayed removal of status and navigation bar

        // Note that some of these constants are new as of API 16 (Jelly Bean)
        // and API 19 (KitKat). It is safe to use them, as they are inlined
        // at compile-time and do nothing on earlier devices.
        image_pager.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LOW_PROFILE or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }
    private val mShowPart2Runnable = Runnable {
        // Delayed display of UI elements
        supportActionBar?.show()
    }
    private var mVisible: Boolean = false
    private val mHideRunnable = Runnable { hide() }

    var currentScaleType = ScaleType.FitPage
    var archive: Archive? = null
        private set
    private var currentPage = 0
    private var retryCount = 0
    private var rtol = false
    private var volControl = false
    private val loadedPages = mutableListOf<Boolean>()
    private var optionsMenu: Menu? = null
    private lateinit var failedMessage: TextView
    private lateinit var imagePager: ViewPager
    private val pageFragments = mutableListOf<PageFragment>()
    private var autoHideDelay = AUTO_HIDE_DELAY_MILLIS
    private var autoHideEnabled = true
    private var retrying = AtomicBoolean()
    private val subtitle: String
        get() {
            return archive.let {
                if (it != null && it.numPages > 0)
                    "Page ${currentPage + 1}/${it.numPages}"
                else
                    "Page ${currentPage + 1}"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        retryCount = 0
        setContentView(R.layout.activity_reader)
        val appBar: Toolbar = findViewById(R.id.reader_toolbar)
        setSupportActionBar(appBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_arrow_back_white_24dp)
        supportActionBar?.title = ""
        currentScaleType = ScaleType.fromInt(savedInstanceState?.getInt(SCALE_TYPE, 0) ?: 0)!!

        ViewCompat.setOnApplyWindowInsetsListener(appBar) { _, insets ->
            var params = FrameLayout.LayoutParams(appBar.layoutParams)
            var safeTop = insets.displayCutout?.safeInsetTop ?: 0
            safeTop = if (safeTop > 0) safeTop else insets.systemWindowInsetTop
            val safeBottom = insets.displayCutout?.safeInsetBottom
            val safeRight = insets.displayCutout?.safeInsetRight ?: insets.systemWindowInsetRight
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
        autoHideDelay = truncate(prefs.castStringPrefToFloat(getString(R.string.fullscreen_timeout_key), AUTO_HIDE_DELAY_S) * 1000).toInt()
        autoHideEnabled = autoHideDelay >= 0

        mVisible = true

        imagePager = findViewById(R.id.image_pager)
        imagePager.adapter = ReaderFragmentAdapter(supportFragmentManager)
        imagePager.addOnPageChangeListener(object: ViewPager.OnPageChangeListener{
            override fun onPageScrollStateChanged(page: Int) {
            }

            override fun onPageScrolled(p0: Int, p1: Float, p2: Int) {
            }

            override fun onPageSelected(page: Int) {
                currentPage = getAdjustedPage(page)
                archive?.let {
                    ReaderTabHolder.updatePageIfTabbed(it.id, currentPage)
                    val markCompletePage = floor(it.numPages * 0.9f).toInt()
                    if (it.numPages > 0 && currentPage + 1 == markCompletePage)
                        launch(Dispatchers.IO) { DatabaseReader.setArchiveNewFlag(it.id) }
                }

                loadImage(page)
                supportActionBar?.subtitle = subtitle
            }

        } )

        failedMessage = findViewById(R.id.failed_message)
        failedMessage.setOnClickListener { toggle() }

        val bundle = intent.extras
        val arcid = bundle?.getString(ID_STRING) ?: savedInstanceState?.getString(ID_STRING)
        val savedPage = when {
            savedInstanceState?.containsKey(CURRENT_PAGE_ID) == true -> savedInstanceState.getInt(CURRENT_PAGE_ID)
            bundle?.containsKey(PAGE_ID) == true -> bundle.getInt(PAGE_ID)
            else -> null
        }
        if (arcid != null) {
            launch {
                archive = withContext(Dispatchers.IO) { DatabaseReader.getArchive(arcid) }
                archive?.let {
                    supportActionBar?.title = it.title
                    //Use the page from the thumbnail over the bookmark
                    val page = savedPage ?: max(it.currentPage, 0)
                    currentPage = page
                    supportActionBar?.subtitle = subtitle
                    setTabbedIcon(ReaderTabHolder.isTabbed(it))

                    //Make sure the archive has been extracted if rtol is set since we need the page count
                    //to get the adjusted page number.
                    if (rtol && !it.isExtracted) {
                        val loadLayout: View = findViewById(R.id.load_layout)
                        loadLayout.visibility = View.VISIBLE
                        loadLayout.setOnClickListener { toggle() }
                        withContext(Dispatchers.IO) { it.extract() }
                        loadLayout.visibility = View.GONE
                    }

                    val adjustedPage = getAdjustedPage(page)
                    loadImage(adjustedPage)
                    imagePager.setCurrentItem(adjustedPage, false)

                    for (listener in pageFragments)
                        listener.onArchiveLoad(it)
                }
            }
        }
    }

    fun registerPage(listener: PageFragment) = pageFragments.add(listener)

    fun unregisterPage(listener: PageFragment) = pageFragments.remove(listener)

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

    private fun getAdjustedPage(page: Int) = if (rtol) archive!!.numPages - page - 1 else page

    private fun adjustLoadedPages(page: Int) : Boolean {
        if (page >= loadedPages.size) {
            val currentSize = loadedPages.size
            for (i in currentSize..page)
                loadedPages.add(false)
        }
        else if (loadedPages[page])
            return false
        loadedPages[page] = true
        return true
    }

    private fun loadImage(page: Int, preload: Boolean = true) {
        if (!archive!!.hasPage(page)) {
            if (archive!!.numPages == 0) {
                failedMessage.visibility = View.VISIBLE
                imagePager.visibility = View.INVISIBLE
            }
            return
        }
        imagePager.visibility = View.VISIBLE
        failedMessage.visibility = View.GONE

        if (adjustLoadedPages(page))
            imagePager.adapter?.notifyDataSetChanged()

        if (preload) {
            for (i in (-1..1)) {
                val newPage = page + i
                if (newPage >= 0 && (newPage >= loadedPages.size || !loadedPages[page + i]))
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
        val bookmarker = menu?.findItem(R.id.bookmark_archive)
        setTabbedIcon(bookmarker, ReaderTabHolder.isTabbed(archive))
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
                        withContext(Dispatchers.IO) { extract() }
                        loadedPages.clear()
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
                    if (!ReaderTabHolder.isTabbed(it)) {
                        ReaderTabHolder.addTab(it, currentPage)
                        setTabbedIcon(item, true)
                    }
                    else {
                        ReaderTabHolder.removeTab(it.id)
                        setTabbedIcon(item, false)
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
        imagePager.setCurrentItem(adjustedPage, false)
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

    private fun hide() {
        // Hide UI first
        supportActionBar?.hide()
        mVisible = false

        // Schedule a runnable to remove the status and navigation bar after a delay
        mHideHandler.removeCallbacks(mShowPart2Runnable)
        mHideHandler.postDelayed(mHidePart2Runnable, UI_ANIMATION_DELAY.toLong())
    }

    private fun show() {
        // Show the system bar
        imagePager.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        mVisible = true

        // Schedule a runnable to display UI elements after a delay
        mHideHandler.removeCallbacks(mHidePart2Runnable)
        mHideHandler.postDelayed(mShowPart2Runnable, UI_ANIMATION_DELAY.toLong())

        if (autoHideEnabled)
            delayedHide(autoHideDelay)
    }

    /**
     * Schedules a call to hide() in [delayMillis], canceling any
     * previously scheduled calls.
     */
    private fun delayedHide(delayMillis: Int) {
        mHideHandler.removeCallbacks(mHideRunnable)
        mHideHandler.postDelayed(mHideRunnable, delayMillis.toLong())
    }

    override fun onFragmentTap(zone: TouchZone) {
        when (zone) {
            TouchZone.Center -> toggle()
            TouchZone.Left -> imagePager.setCurrentItem(imagePager.currentItem - 1, false)
            TouchZone.Right -> imagePager.setCurrentItem(imagePager.currentItem + 1, false)
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

    override fun onImageLoadError(fragment: ReaderFragment) : Boolean {
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

    companion object {
        private const val AUTO_HIDE_DELAY_S = 5f
        /**
         * If [autoHideEnabled] is set, the number of milliseconds to wait after
         * user interaction before hiding the system UI.
         */
        private const val AUTO_HIDE_DELAY_MILLIS = AUTO_HIDE_DELAY_S.toInt() * 1000

        /**
         * Some older devices needs a small delay between UI widget updates
         * and a change of the status and navigation bar.
         */
        private const val UI_ANIMATION_DELAY = 300

        private const val SCALE_TYPE = "scale_type"
    }

    private inner class ReaderFragmentAdapter(fm: FragmentManager) : FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getItem(position: Int) = ReaderFragment.createInstance(getAdjustedPage(position))

        override fun getCount(): Int = loadedPages.size
    }
}

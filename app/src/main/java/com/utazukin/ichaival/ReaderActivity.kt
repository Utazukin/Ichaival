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

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.preference.PreferenceManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
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

private const val ID_STRING = "id"
private const val PAGE_ID = "page"
private const val CURRENT_PAGE_ID = "currentPage"

class ReaderActivity : BaseActivity(), OnFragmentInteractionListener, TabRemovedListener, TabsClearedListener {
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

    var archive: Archive? = null
        private set
    private var currentPage = 0
    private var rtol = false
    private val loadedPages = mutableListOf<Boolean>()
    private var optionsMenu: Menu? = null
    private lateinit var failedMessage: TextView
    private lateinit var imagePager: ViewPager
    private val subtitle: String
        get() {
            val arc = archive
            return if (arc != null && arc.numPages > 0)
                "Page ${currentPage + 1}/${arc.numPages}"
            else
                "Page ${currentPage + 1}"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_reader)
        setSupportActionBar(findViewById(R.id.reader_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        rtol = prefs.getBoolean(getString(R.string.rtol_pref_key), false)

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
                ReaderTabHolder.updatePageIfTabbed(archive!!.id, currentPage)
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
                archive = withContext(Dispatchers.Default) { DatabaseReader.getArchive(arcid, applicationContext.filesDir) }
                archive?.let {
                    supportActionBar?.title = it.title
                    //Use the page from the thumbnail over the bookmark
                    val page = savedPage ?: ReaderTabHolder.getCurrentPage(arcid)
                    val adjustedPage = getAdjustedPage(page)
                    currentPage = page
                    loadImage(adjustedPage)
                    imagePager.setCurrentItem(adjustedPage, false)
                    supportActionBar?.subtitle = subtitle

                    setTabbedIcon(ReaderTabHolder.isTabbed(arcid))
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(CURRENT_PAGE_ID, currentPage)
        outState.putString(PAGE_ID, archive?.id)
    }

    override fun onCreateDrawer() {
        drawerLayout = findViewById(R.id.drawer_layout)
        val tabView: RecyclerView = findViewById(R.id.tab_view)
        val listener = this
        with(tabView) {
            layoutManager = LinearLayoutManager(context)
            adapter = ReaderTabViewAdapter(listener, listener, Glide.with(listener))

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

    override fun onTabRemoved(index: Int, id: String) {
        if (id == archive?.id)
            setTabbedIcon(false)
    }

    override fun onTabsCleared(oldSize: Int) = setTabbedIcon(false)

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
            if (archive!!.numPages == 0)
                failedMessage.visibility = View.VISIBLE
            return
        }
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
        delayedHide(100)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.reader_menu, menu)
        optionsMenu = menu
        val bookmarker = menu?.findItem(R.id.bookmark_archive)
        setTabbedIcon(bookmarker, ReaderTabHolder.isTabbed(archive?.id))
        return super.onCreateOptionsMenu(menu)
    }

    private fun setTabbedIcon(menuItem: MenuItem?, tabbed: Boolean) {
        val icon = if (tabbed)
            R.drawable.ic_bookmark_white_24dp
        else
            R.drawable.ic_bookmark_border_white_24dp
        menuItem?.icon = getDrawable(icon)

    }

    override fun onStop() {
        super.onStop()
        Glide.get(this).clearMemory()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.bookmark_archive -> {
                archive?.let {
                    if (!ReaderTabHolder.isTabbed(it.id)) {
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
            R.id.detail_menu -> {
                archive?.let {
                    setResult(Activity.RESULT_OK)
                    startDetailsActivity(it.id)
                    finish()
                    return true
                }
            }
            R.id.goto_menu -> {
                archive?.let {
                    if (it.numPages >= 0) {
                        val dialog = PageSelectDialogFragment.createInstance(currentPage + 1, it.numPages)
                        dialog.listener = { value ->
                            loadImage(value)
                            imagePager.setCurrentItem(value, false)
                        }
                        dialog.show(supportFragmentManager, "page_picker")
                    }
                }
            }
            R.id.refresh_item -> {
                archive?.run {
                    invalidateCache()
                    launch {
                        withContext(Dispatchers.Default) { extract() }
                        loadedPages.clear()
                        loadImage(currentPage)
                    }
                }
            }
        }
        return super.onOptionsItemSelected(item)
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

        if (AUTO_HIDE)
            delayedHide(AUTO_HIDE_DELAY_MILLIS)
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

    override fun onImageLoadError(fragment: ReaderFragment) {
        archive?.run {
            invalidateCache()
            launch {
                withContext(Dispatchers.Default) { extract() }
                fragment.reloadImage()
            }
        }
    }

    companion object {
        /**
         * Whether or not the system UI should be auto-hidden after
         * [AUTO_HIDE_DELAY_MILLIS] milliseconds.
         */
        private const val AUTO_HIDE = true

        /**
         * If [AUTO_HIDE] is set, the number of milliseconds to wait after
         * user interaction before hiding the system UI.
         */
        private const val AUTO_HIDE_DELAY_MILLIS = 5000

        /**
         * Some older devices needs a small delay between UI widget updates
         * and a change of the status and navigation bar.
         */
        private const val UI_ANIMATION_DELAY = 300
    }

    private inner class ReaderFragmentAdapter(fm: FragmentManager) : FragmentStatePagerAdapter(fm) {

        override fun getItem(position: Int) = ReaderFragment.createInstance(getAdjustedPage(position))

        override fun getCount(): Int = loadedPages.size
    }
}

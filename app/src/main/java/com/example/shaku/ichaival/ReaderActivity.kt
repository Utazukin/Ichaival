package com.example.shaku.ichaival

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NavUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.bumptech.glide.Glide
import com.example.shaku.ichaival.ReaderFragment.OnFragmentInteractionListener
import com.example.shaku.ichaival.ReaderTabViewAdapter.OnTabInteractionListener
import kotlinx.android.synthetic.main.activity_reader.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
class ReaderActivity : AppCompatActivity(), OnTabInteractionListener, OnFragmentInteractionListener {
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
    /**
     * Touch listener to use for in-layout UI controls to delay hiding the
     * system UI. This is to prevent the jarring behavior of controls going away
     * while interacting with activity UI.
     */
    private val mDelayHideTouchListener = View.OnTouchListener { _, _ ->
        if (AUTO_HIDE) {
            delayedHide(AUTO_HIDE_DELAY_MILLIS)
        }
        false
    }

    private var archive: Archive? = null
    private var currentPage = 0
    private val loadedPages = mutableListOf<Boolean>()
    private lateinit var optionsMenu: Menu

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_reader)
        setSupportActionBar(findViewById(R.id.reader_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        mVisible = true

        image_pager.adapter = ReaderFragmentAdapter(supportFragmentManager)
        image_pager.addOnPageChangeListener(object: ViewPager.OnPageChangeListener{
            override fun onPageScrollStateChanged(page: Int) {
            }

            override fun onPageScrolled(p0: Int, p1: Float, p2: Int) {
            }

            override fun onPageSelected(page: Int) {
                currentPage = page
                ReaderTabHolder.updatePageIfTabbed(archive!!.id, page)
                loadImage(currentPage)
            }

        } )

        val bundle = intent.extras
        if (bundle != null) {
            val arcid = bundle.getString("id")
            val savedPage = if (bundle.containsKey("page")) bundle.getInt("page") else null
            if (arcid != null) {
                GlobalScope.launch(Dispatchers.Main) {
                    archive = async { DatabaseReader.getArchive(arcid, applicationContext.filesDir) }.await()
                    val copy = archive
                    if (copy != null) {
                        supportActionBar?.title = copy.title
                        //Use the page from the thumbnail over the bookmark
                        val page = savedPage ?: ReaderTabHolder.getCurrentPage(arcid)
                        currentPage = page
                        loadImage(page)
                        image_pager.setCurrentItem(page, false)
                    }
                }
            }
        }

        val tabView: RecyclerView = findViewById(R.id.tab_view)
        val listener = this
        with(tabView) {
            layoutManager = LinearLayoutManager(context)
            adapter = ReaderTabViewAdapter(ReaderTabHolder.getTabList(), listener)
        }

        val swipeHandler = object: ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            override fun onMove(p0: RecyclerView, p1: RecyclerView.ViewHolder, p2: RecyclerView.ViewHolder): Boolean {
                return false
            }

            override fun onSwiped(holder: RecyclerView.ViewHolder, p1: Int) {
                val adapter = tabView.adapter as ReaderTabViewAdapter
                adapter.removeTab(holder.adapterPosition)
                val bookmarker = optionsMenu.findItem(R.id.bookmark_archive)
                setTabbedIcon(bookmarker, false)
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(tabView)
    }

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
        if (!archive!!.hasPage(page))
            return

        if (adjustLoadedPages(page))
            image_pager.adapter?.notifyDataSetChanged()

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
        optionsMenu = menu!!
        val bookmarker = menu.findItem(R.id.bookmark_archive)
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
        val id = item.itemId
        when (id) {
            // This ID represents the Home or Up button.
            android.R.id.home -> {
                NavUtils.navigateUpFromSameTask(this)
                return true
            }
            R.id.bookmark_archive -> {
                val copy = archive
                if (copy != null) {
                    if (!ReaderTabHolder.isTabbed(copy.id)) {
                        ReaderTabHolder.addTab(copy, currentPage)
                        setTabbedIcon(item, true)
                    }
                    else {
                        ReaderTabHolder.removeTab(copy.id)
                        setTabbedIcon(item, false)
                    }
                    return true
                }
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onTabInteraction(tab: ReaderTab) {
        val intent = Intent(this, ReaderActivity::class.java)
        val bundle = Bundle()
        bundle.putString("id", tab.id)
        intent.putExtras(bundle)
        startActivity(intent)
        finish()
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
        image_pager.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        mVisible = true

        // Schedule a runnable to display UI elements after a delay
        mHideHandler.removeCallbacks(mHidePart2Runnable)
        mHideHandler.postDelayed(mShowPart2Runnable, UI_ANIMATION_DELAY.toLong())
    }

    /**
     * Schedules a call to hide() in [delayMillis], canceling any
     * previously scheduled calls.
     */
    private fun delayedHide(delayMillis: Int) {
        mHideHandler.removeCallbacks(mHideRunnable)
        mHideHandler.postDelayed(mHideRunnable, delayMillis.toLong())
    }

    override fun onFragmentTap() {
        toggle()
    }

    companion object {
        /**
         * Whether or not the system UI should be auto-hidden after
         * [AUTO_HIDE_DELAY_MILLIS] milliseconds.
         */
        private val AUTO_HIDE = true

        /**
         * If [AUTO_HIDE] is set, the number of milliseconds to wait after
         * user interaction before hiding the system UI.
         */
        private val AUTO_HIDE_DELAY_MILLIS = 3000

        /**
         * Some older devices needs a small delay between UI widget updates
         * and a change of the status and navigation bar.
         */
        private val UI_ANIMATION_DELAY = 300
    }

    private inner class ReaderFragmentAdapter(fm: FragmentManager) : FragmentStatePagerAdapter(fm) {

        override fun getItem(position: Int): Fragment {
            val fragment = ReaderFragment()
            GlobalScope.launch(Dispatchers.Main) {
                val image = GlobalScope.async { archive?.getPageImage(position) }.await()
                fragment.displayImage(image, position)
            }
            return fragment
        }

        override fun getCount(): Int = loadedPages.size
    }
}

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

package com.utazukin.ichaival

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.drawerlayout.widget.DrawerLayout.DrawerListener
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.divider.MaterialDividerItemDecoration
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import com.utazukin.ichaival.ReaderTabViewAdapter.OnTabInteractionListener
import com.utazukin.ichaival.database.DatabaseMessageListener
import com.utazukin.ichaival.database.DatabaseReader
import com.utazukin.ichaival.reader.ReaderActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

const val TAG_SEARCH = "tag"
const val REFRESH_KEY = "refresh"

abstract class BaseActivity : AppCompatActivity(), DatabaseMessageListener, OnTabInteractionListener, TabAddedListener, CoroutineScope {
    override val coroutineContext = lifecycleScope.coroutineContext
    protected lateinit var drawerLayout: DrawerLayout
    protected lateinit var navView: NavigationView
    private lateinit var tabView: RecyclerView
    private val backPressedCallback = object: OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = handleBackPressed()
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        onCreateDrawer()
    }

    override fun setSupportActionBar(toolbar: Toolbar?) {
        super.setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme()

        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        if (WebHandler.serverLocation.isNotEmpty()) {
            val refresh = intent.getBooleanExtra(REFRESH_KEY, false)
            launch {
                val supported = ServerManager.init(applicationContext, !refresh && savedInstanceState != null, refresh)
                onServerInitialized(supported)
            }
        }
        intent.removeExtra(REFRESH_KEY)
    }

    protected open fun onServerInitialized(serverSupported: InfoResult) {}

    protected open fun setTheme() {
        when (getCustomTheme()) {
            getString(R.string.dark_theme) -> setTheme(R.style.AppTheme)
            getString(R.string.black_theme) -> setTheme(R.style.AppTheme_Black)
            getString(R.string.material_theme) -> setTheme(R.style.MaterialYou)
            getString(R.string.white_theme) -> setTheme(R.style.AppTheme_White)
        }
    }

    protected open fun onCreateDrawer() {
        drawerLayout = findViewById(R.id.drawer_layout)
        drawerLayout.setStatusBarBackgroundColor(MaterialColors.getColor(drawerLayout, R.attr.colorSurface))

        drawerLayout.addDrawerListener(object: DrawerListener{
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
            override fun onDrawerOpened(drawerView: View) {
                backPressedCallback.isEnabled = true
            }
            override fun onDrawerClosed(drawerView: View) {
                backPressedCallback.isEnabled = false
            }
            override fun onDrawerStateChanged(newState: Int) {}
        })

        navView = drawerLayout.findViewById(R.id.nav_view)
        tabView = findViewById(R.id.tab_view)
        with(tabView) {
            layoutManager = LinearLayoutManager(context)
            adapter = ReaderTabViewAdapter(this@BaseActivity).also { setupReaderTabAdapter(it) }

            val dividerDecoration = MaterialDividerItemDecoration(context, LinearLayoutManager.VERTICAL)
            addItemDecoration(dividerDecoration)
        }

        val closeButton: ImageView = findViewById(R.id.clear_bookmark)
        closeButton.setOnClickListener{
            launch {
                val bookmarks = DatabaseReader.getBookmarks()
                if (bookmarks.isNotEmpty()) {
                    with(Snackbar.make(navView, R.string.cleared_bookmarks_snack, Snackbar.LENGTH_LONG)) {
                        ReaderTabHolder.removeAll()
                        setAction(R.string.undo) { ReaderTabHolder.addReaderTabs(bookmarks) }
                        addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                                super.onDismissed(transientBottomBar, event)
                                if (event != DISMISS_EVENT_ACTION)
                                    ReaderTabHolder.resetServerProgress(bookmarks)
                            }
                        })
                        show()
                    }
                }
            }
        }

        val touchHelper = BookmarkTouchHelper(this) { tab, position, direction ->
            when (direction) {
                ItemTouchHelper.LEFT -> handleTabSwipeLeft(tab, position)
                ItemTouchHelper.RIGHT -> {
                    with(Snackbar.make(navView, R.string.bookmark_removed_snack, Snackbar.LENGTH_LONG)) {
                        ReaderTabHolder.removeTab(tab.id)
                        setAction(R.string.undo) { ReaderTabHolder.insertTab(tab) }
                        addCallback(object: BaseTransientBottomBar.BaseCallback<Snackbar>() {
                            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                                super.onDismissed(transientBottomBar, event)
                                if (event != DISMISS_EVENT_ACTION)
                                    ReaderTabHolder.resetServerProgress(tab.id)
                            }
                        })
                        show()
                    }
                }
            }
        }
        ItemTouchHelper(touchHelper).attachToRecyclerView(tabView)
    }

    protected open fun handleTabSwipeLeft(tab: ReaderTab, position: Int) {
        tabView.adapter?.notifyItemChanged(position)
        startDetailsActivity(tab.id)
    }

    protected open fun setupReaderTabAdapter(adapter: ReaderTabViewAdapter) {
        adapter.registerAdapterDataObserver(object: RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                if (itemCount == 1 && positionStart == adapter.itemCount - 1)
                    tabView.scrollToPosition(positionStart)
            }
        })
    }

    protected open fun handleBackPressed() {
        if (drawerLayout.isDrawerOpen(navView))
            drawerLayout.closeDrawer(navView)
    }

    override fun onStart() {
        super.onStart()
        WebHandler.listener = this
    }

    override fun onPause() {
        super.onPause()
        WebHandler.listener = null
    }

    override fun onStop() {
        super.onStop()
        drawerLayout.closeDrawers()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        WebHandler.serverLocation = prefs.getString(getString(R.string.server_address_preference), "")!!
        WebHandler.apiKey = prefs.getString(getString(R.string.api_key_pref), "")!!
    }

    override fun onError(error: String) {
        launch { Toast.makeText(this@BaseActivity, getString(R.string.error_message, error), Toast.LENGTH_LONG).show() }
    }

    override fun onInfo(message: String) {
        launch { Toast.makeText(this@BaseActivity, message, Toast.LENGTH_SHORT).show() }
    }

    override fun onTabAdded(id: String) {
        drawerLayout.openDrawer(navView, true)
    }

    override fun onTabsAdded(ids: List<String>) {
        drawerLayout.openDrawer(navView, true)
    }

    override fun onTabInteraction(tab: ReaderTab) = startReaderActivity(tab.id)

    override fun onLongPressTab(tab: ReaderTab): Boolean {
        val tagFragment = TagDialogFragment.newInstance(tab.id)
        tagFragment.show(supportFragmentManager, "tag_popup")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when(item.itemId) {
            android.R.id.home -> {
                drawerLayout.openDrawer(GravityCompat.START)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    protected fun startReaderActivity(id: String) {
        val intent = Intent(this, ReaderActivity::class.java)
        val bundle = Bundle()
        bundle.putString("id", id)
        intent.putExtras(bundle)
        addIntentFlags(intent, id)
        startActivity(intent)
    }

    protected open fun addIntentFlags(intent: Intent, id: String) { }

    protected open fun startDetailsActivity(id: String){
        val intent = Intent(this, ArchiveDetails::class.java)
        val bundle = Bundle()
        bundle.putString("id", id)
        intent.putExtras(bundle)
        addIntentFlags(intent, id)
        startActivity(intent)
    }
}
/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2022 Utazukin
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

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.MenuItem
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProviders
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import com.utazukin.ichaival.ReaderTabViewAdapter.OnTabInteractionListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

const val TAG_SEARCH = "tag"

abstract class BaseActivity : AppCompatActivity(), DatabaseMessageListener, OnTabInteractionListener, TabAddedListener, CoroutineScope by MainScope() {
    protected lateinit var drawerLayout: DrawerLayout
    protected lateinit var navView: NavigationView
    private lateinit var tabView: RecyclerView
    protected var needsRefresh = false

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
        CrashLogger.createCrashLogger(this)
        DatabaseReader.init(applicationContext)
        WebHandler.connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        ReaderTabHolder.initialize(this)
        setTheme()

        super.onCreate(savedInstanceState)

        if (WebHandler.serverLocation.isNotEmpty()) {
            val refresh = needsRefresh
            launch {
                withContext(Dispatchers.IO) { ServerManager.init(applicationContext, !refresh && savedInstanceState != null, refresh) }
                onServerInitialized()
            }
        }
        needsRefresh = false

    }

    protected open fun onServerInitialized() {}

    protected open fun setTheme() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        when (prefs.getString(getString(R.string.theme_pref), getString(R.string.dark_theme))) {
            getString(R.string.dark_theme) -> setTheme(R.style.AppTheme)
            getString(R.string.black_theme) -> setTheme(R.style.AppTheme_Black)
        }
    }

    protected open fun onCreateDrawer() {
        drawerLayout = findViewById(R.id.drawer_layout)
        navView = drawerLayout.findViewById(R.id.nav_view)
        val context = this
        tabView = findViewById(R.id.tab_view)
        val viewModel = ViewModelProviders.of(this)[ReaderTabViewModel::class.java]
        with(tabView) {
            layoutManager = LinearLayoutManager(context)
            adapter = ReaderTabViewAdapter(this@BaseActivity).also {
                it.registerAdapterDataObserver(object: RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                        super.onItemRangeInserted(positionStart, itemCount)
                        scrollToPosition(positionStart)
                    }
                })
                launch(Dispatchers.Default) {
                    viewModel.bookmarks.collectLatest { data -> it.submitData(data) }
                }
            }

            val dividerDecoration = DividerItemDecoration(context, LinearLayoutManager.VERTICAL)
            addItemDecoration(dividerDecoration)
        }

        val closeButton: ImageView = findViewById(R.id.clear_bookmark)
        closeButton.setOnClickListener{ ReaderTabHolder.removeAll() }

        val touchHelper = BookmarkTouchHelper(this) { id, position ->
            tabView.adapter?.notifyItemChanged(position)
            startDetailsActivity(id)
        }
        ItemTouchHelper(touchHelper).attachToRecyclerView(tabView)
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(navView))
            drawerLayout.closeDrawer(navView)
        else
            super.onBackPressed()
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

    override fun onDestroy() {
        super.onDestroy()
        cancel()
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
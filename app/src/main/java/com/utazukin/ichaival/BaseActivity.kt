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

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.PersistableBundle
import android.preference.PreferenceManager
import android.view.MenuItem
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.navigation.NavigationView
import com.utazukin.ichaival.ReaderTabViewAdapter.OnTabInteractionListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

const val TAG_SEARCH = "tag"

abstract class BaseActivity : AppCompatActivity(), DatabaseMessageListener, OnTabInteractionListener, TabAddedListener,
    CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job
    protected lateinit var drawerLayout: DrawerLayout
    protected lateinit var navView: NavigationView
    private lateinit var tabView: RecyclerView
    private val job: Job by lazy { Job() }

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

    override fun onCreate(savedInstanceState: Bundle?, persistentState: PersistableBundle?) {
        super.onCreate(savedInstanceState, persistentState)
        DatabaseReader.init(applicationContext)
    }

    protected open fun onCreateDrawer() {
        drawerLayout = findViewById(R.id.drawer_layout)
        navView = drawerLayout.findViewById(R.id.nav_view)
        val context = this
        tabView = findViewById(R.id.tab_view)
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
        touchHelper.leftSwipeListener = { id, position ->
            tabView.adapter?.notifyItemChanged(position)
            startDetailsActivity(id)
        }
        ItemTouchHelper(touchHelper).attachToRecyclerView(tabView)
    }

    override fun onStart() {
        super.onStart()
        DatabaseReader.listener = this
        DatabaseReader.connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    override fun onPause() {
        super.onPause()
        DatabaseReader.listener = null
        DatabaseReader.connectivityManager = null
    }

    override fun onStop() {
        super.onStop()
        drawerLayout.closeDrawers()
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle?) {
        super.onRestoreInstanceState(savedInstanceState)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
        DatabaseReader.updateServerLocation(prefs.getString(getString(R.string.server_address_preference), ""))

        @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
        DatabaseReader.updateApiKey(prefs.getString(getString(R.string.api_key_pref), ""))

        ReaderTabHolder.restoreTabs(savedInstanceState)
    }

    override fun onError(error: String) {
        val context = this
        launch { Toast.makeText(context, "Error: $error", Toast.LENGTH_LONG).show() }
    }

    override fun onExtract(title: String) {
        val context = this
        launch { Toast.makeText(context, "Extracting...", Toast.LENGTH_LONG).show() }
    }

    override fun onTabAdded(index: Int, id: String) {
        drawerLayout.openDrawer(navView, true)
        tabView.scrollToPosition(index)
    }

    override fun onTabInteraction(tab: ReaderTab) = startReaderActivity(tab.id)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        ReaderTabHolder.saveTabs(outState)
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

    private fun startReaderActivity(id: String) {
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
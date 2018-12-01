/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2018 Utazukin
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
import android.preference.PreferenceActivity
import android.preference.PreferenceManager
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import com.utazukin.ichaival.ArchiveListFragment.OnListFragmentInteractionListener

class ArchiveList : BaseActivity(), OnListFragmentInteractionListener, ReaderTabViewAdapter.OnTabInteractionListener, TabAddedListener {
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView

    override fun onListFragmentInteraction(archive: Archive?) {
        if (archive != null)
            startDetailsActivity(archive.id)
    }

    private fun startReaderActivity(id: String) {
        val intent = Intent(this, ReaderActivity::class.java)
        val bundle = Bundle()
        bundle.putString("id", id)
        intent.putExtras(bundle)
        startActivity(intent)
    }

    private fun startDetailsActivity(id: String){
        val intent = Intent(this, ArchiveDetails::class.java)
        val bundle = Bundle()
        bundle.putString("id", id)
        intent.putExtras(bundle)
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_archive_list)
        setSupportActionBar(findViewById(R.id.toolbar))

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
        DatabaseReader.updateServerLocation(prefs.getString(getString(R.string.server_address_preference), ""))

        @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
        DatabaseReader.updateApiKey(prefs.getString(getString(R.string.api_key_pref), ""))

        drawerLayout = findViewById(R.id.drawer_layout)
        navView = drawerLayout.findViewById(R.id.nav_view)
        val context = this
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings -> {
                    val intent = Intent(context, SettingsActivity::class.java)
                    intent.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT, SettingsActivity.GeneralPreferenceFragment::class.java.name)
                    intent.putExtra(PreferenceActivity.EXTRA_NO_HEADERS, true)
                    startActivity(intent)
                    drawerLayout.closeDrawers()
                    true
                }
                else -> false
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
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(tabView)
    }

    override fun onStart() {
        super.onStart()
        ReaderTabHolder.registerAddListener(this)
    }

    override fun onStop() {
        super.onStop()
        ReaderTabHolder.unregisterAddListener(this)
    }

    override fun onTabAdded(index: Int, id: String) {
        drawerLayout.openDrawer(navView, true)
    }

    override fun onTabInteraction(tab: ReaderTab) {
        startReaderActivity(tab.id)
        drawerLayout.closeDrawers()
    }
}

/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2020 Utazukin
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
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.preference.PreferenceManager
import com.google.android.material.navigation.NavigationView
import com.utazukin.ichaival.ArchiveListFragment.OnListFragmentInteractionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ArchiveList : BaseActivity(), OnListFragmentInteractionListener, SharedPreferences.OnSharedPreferenceChangeListener, CategoryListener {
    private lateinit var setupText: TextView

    override fun onListFragmentInteraction(archive: Archive?) {
        if (archive != null)
            startDetailsActivity(archive.id)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(this)

        val serverSetting = prefs.getString(getString(R.string.server_address_preference), "") as String
        WebHandler.serverLocation = serverSetting
        updatePreferences(prefs)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_archive_list)
        setSupportActionBar(findViewById(R.id.toolbar))

        setupText = findViewById(R.id.first_time_text)
        handleSetupText(serverSetting.isEmpty())
    }

    private fun updatePreferences(prefs: SharedPreferences) {
        WebHandler.apiKey = prefs.getString(getString(R.string.api_key_pref), "") as String
        WebHandler.verboseMessages = prefs.getBoolean(getString(R.string.verbose_pref), false)
    }

    override fun onSharedPreferenceChanged(pref: SharedPreferences, key: String) {
        when (key) {
            getString(R.string.server_address_preference) -> {
                val location = pref.getString(key, "") as String
                val listFragment: ArchiveListFragment? =
                    supportFragmentManager.findFragmentById(R.id.list_fragment) as ArchiveListFragment?
                WebHandler.serverLocation = location
                handleSetupText(location.isEmpty())
                listFragment?.forceArchiveListUpdate()
            }
            getString(R.string.api_key_pref) -> {
                WebHandler.apiKey = pref.getString(key, "") as String
                val listFragment: ArchiveListFragment? =
                    supportFragmentManager.findFragmentById(R.id.list_fragment) as ArchiveListFragment?
                listFragment?.forceArchiveListUpdate()
            }
            getString(R.string.verbose_pref) -> WebHandler.verboseMessages = pref.getBoolean(key, false)
        }
    }

    override fun onCreateDrawer() {
        super.onCreateDrawer()
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings -> {
                    startSettingsActivity()
                    drawerLayout.closeDrawers()
                    true
                }
                else -> false
            }
        }
    }

    override fun onServerInitialized() {
        super.onServerInitialized()
        val listFragment: ArchiveListFragment? = supportFragmentManager.findFragmentById(R.id.list_fragment) as ArchiveListFragment?
        listFragment?.setupArchiveList()

        val categories = ServerManager.categories
        if (categories == null) {
            val filterSheet: NavigationView = findViewById(R.id.category_filter_view)
            val parent = filterSheet.parent as ViewGroup
            parent.removeView(filterSheet)
        } else {
            val categoryFragment: CategoryFilterFragment? = supportFragmentManager.findFragmentById(R.id.category_fragment) as CategoryFilterFragment?
            categoryFragment?.initCategories(categories)
        }
    }

    override fun onCategoryChanged(category: ArchiveCategory) {
        val listFragment: ArchiveListFragment? = supportFragmentManager.findFragmentById(R.id.list_fragment) as ArchiveListFragment?
        listFragment?.handleCategoryChange(category)
    }

    private fun handleSetupText(setup: Boolean) {
        if (setup)
            setupText.setOnClickListener { startSettingsActivity() }
        else
            setupText.visibility = View.GONE
    }

    private fun startSettingsActivity() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    override fun onStart() {
        super.onStart()
        ReaderTabHolder.registerAddListener(this)
        launch(Dispatchers.IO) { ServerManager.generateTagSuggestions() }
    }

    override fun onStop() {
        super.onStop()
        ReaderTabHolder.unregisterAddListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }
}

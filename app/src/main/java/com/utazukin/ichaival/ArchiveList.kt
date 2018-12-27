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
import android.view.View
import android.widget.TextView
import com.utazukin.ichaival.ArchiveListFragment.OnListFragmentInteractionListener

class ArchiveList : BaseActivity(), OnListFragmentInteractionListener {
    private lateinit var setupText: TextView

    override fun onListFragmentInteraction(archive: Archive?) {
        if (archive != null)
            startDetailsActivity(archive.id)
    }

    override fun onFragmentLongPress(archive: Archive?, view: View) : Boolean {
        archive?.run {
            val tagFragment = TagDialogFragment.newInstance(id)
            tagFragment.show(supportFragmentManager, "tag_popup")
        }
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_archive_list)
        setSupportActionBar(findViewById(R.id.toolbar))

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener { p, key ->
            when(key) {
                getString(R.string.server_address_preference) -> {
                    val location = p.getString(key, "") as String
                    val listFragment: ArchiveListFragment? = supportFragmentManager.findFragmentById(R.id.list_fragment) as ArchiveListFragment?
                    DatabaseReader.updateServerLocation(location)
                    handleSetupText(location.isEmpty())
                    listFragment?.forceArchiveListUpdate()
                }
                getString((R.string.api_key_pref)) -> {
                    DatabaseReader.updateApiKey(p.getString(key, "") as String)
                    val listFragment: ArchiveListFragment? = supportFragmentManager.findFragmentById(R.id.list_fragment) as ArchiveListFragment?
                    listFragment?.forceArchiveListUpdate()
                }
            }
        }

        val serverSetting = prefs.getString(getString(R.string.server_address_preference), "") as String
        setupText = findViewById(R.id.first_time_text)
        handleSetupText(serverSetting.isEmpty())

        DatabaseReader.updateServerLocation(serverSetting)
        DatabaseReader.updateApiKey(prefs.getString(getString(R.string.api_key_pref), "") as String)
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

    private fun handleSetupText(setup: Boolean) {
        if (setup)
            setupText.setOnClickListener { startSettingsActivity() }
        else
            setupText.visibility = View.GONE
    }

    private fun startSettingsActivity() {
        val intent = Intent(this, SettingsActivity::class.java)
        intent.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT, SettingsActivity.GeneralPreferenceFragment::class.java.name)
        intent.putExtra(PreferenceActivity.EXTRA_NO_HEADERS, true)
        startActivity(intent)
    }

    override fun onStart() {
        super.onStart()
        ReaderTabHolder.registerAddListener(this)
    }

    override fun onStop() {
        super.onStop()
        ReaderTabHolder.unregisterAddListener(this)
    }
}

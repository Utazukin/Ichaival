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
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Bundle
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.app.ActivityOptionsCompat
import androidx.core.util.Pair
import androidx.preference.PreferenceManager
import com.google.android.material.navigation.NavigationView
import com.utazukin.ichaival.ArchiveListFragment.OnListFragmentInteractionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

const val COVER_TRANSITION = "cover"

class ArchiveList : BaseActivity(), OnListFragmentInteractionListener, SharedPreferences.OnSharedPreferenceChangeListener, FilterListener {
    private lateinit var setupText: TextView
    private lateinit var categoryView: NavigationView
    private var currentTheme = ""

    override fun onListFragmentInteraction(archive: Archive?, view: View) {
        if (archive != null)
            startDetailsActivity(archive.id, view)
    }

    private fun startDetailsActivity(id: String, view: View) {
        val intent = Intent(this, ArchiveDetails::class.java).also {
            it.putExtras(Bundle().apply { putString("id", id) })
            addIntentFlags(it, id)
        }
        val coverView: View = view.findViewById(R.id.archive_thumb)
        val statusBar: View = findViewById(android.R.id.statusBarBackground)
        val coverPair = Pair(coverView, COVER_TRANSITION)
        val statusPair = Pair(statusBar, Window.STATUS_BAR_BACKGROUND_TRANSITION_NAME)
        startActivity(intent, ActivityOptionsCompat.makeSceneTransitionAnimation(this, coverPair, statusPair).toBundle())
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

        currentTheme = getCustomTheme()
        setupText = findViewById(R.id.first_time_text)
        handleSetupText(serverSetting.isEmpty())
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (CategoryManager.categories != null)
            menu.findItem(R.id.filter_menu)?.isVisible = true

        return super.onCreateOptionsMenu(menu)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            (currentFocus as? EditText)?.let {
                val outRect = Rect()
                it.getLocalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    it.clearFocus()
                    with(getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager) {
                        hideSoftInputFromWindow(it.windowToken, 0)
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun updatePreferences(prefs: SharedPreferences) {
        WebHandler.apiKey = prefs.getString(getString(R.string.api_key_pref), "") as String
        WebHandler.verboseMessages = prefs.getBoolean(getString(R.string.verbose_pref), false)
    }

    override fun setTheme() {
        super.setTheme()
        currentTheme = getCustomTheme()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (outState.getBoolean(REFRESH_KEY))
            super.onSaveInstanceState(outState)
        else
            outState.remove(REFRESH_KEY)
    }

    override fun onSharedPreferenceChanged(pref: SharedPreferences, key: String) {
        when (key) {
            getString(R.string.server_address_preference) -> {
                val location = pref.getString(key, "") as String
                intent.putExtra(REFRESH_KEY, true)
                WebHandler.serverLocation = location
                handleSetupText(location.isEmpty())
            }
            getString(R.string.api_key_pref) -> {
                WebHandler.apiKey = pref.getString(key, "") as String
                intent.putExtra(REFRESH_KEY, true)
            }
            getString(R.string.verbose_pref) -> WebHandler.verboseMessages = pref.getBoolean(key, false)
            getString(R.string.theme_pref) -> {
                intent.putExtra(REFRESH_KEY, true)
                setTheme()
            }
            getString(R.string.archive_list_type_key) -> intent.putExtra(REFRESH_KEY, true)
        }
    }

    override fun onCreateDrawer() {
        super.onCreateDrawer()
        categoryView = drawerLayout.findViewById(R.id.category_filter_view)
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

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(categoryView))
            drawerLayout.closeDrawer(categoryView)
        else
            super.onBackPressed()
    }

    override fun onLongPressTab(tab: ReaderTab): Boolean {
        val tagFragment = TagDialogFragment.newInstance(tab.id)

        val listFragment = supportFragmentManager.findFragmentById(R.id.list_fragment) as? ArchiveListFragment
        listFragment?.run {
            tagFragment.setTagPressListener { tag ->
                searchView.setQuery(tag, true)
                drawerLayout.closeDrawers()
            }
            tagFragment.setTagLongPressListener { tag ->
                searchView.setQuery("${searchView.query} $tag", true)
                drawerLayout.closeDrawers()
                true
            }
        }

        tagFragment.show(supportFragmentManager, "tag_popup")

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.filter_menu -> {
                drawerLayout.openDrawer(categoryView)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onServerInitialized() {
        super.onServerInitialized()
        ServerManager.serverName?.let { supportActionBar?.title = it }
        val listFragment: ArchiveListFragment? = supportFragmentManager.findFragmentById(R.id.list_fragment) as? ArchiveListFragment
        listFragment?.setupArchiveList()
    }

    override fun onCategoryChanged(category: ArchiveCategory) {
        val listFragment: ArchiveListFragment? = supportFragmentManager.findFragmentById(R.id.list_fragment) as? ArchiveListFragment
        listFragment?.handleCategoryChange(category)
    }

    override fun onSortChanged(sort: SortMethod, desc: Boolean) {
        val listFragment: ArchiveListFragment? = supportFragmentManager.findFragmentById(R.id.list_fragment) as? ArchiveListFragment
        listFragment?.updateSortMethod(sort, desc)
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

    override fun onResume() {
        super.onResume()

        if (intent.getBooleanExtra(REFRESH_KEY, false)) {
            finish()
            startActivity(intent)
            overridePendingTransition(0, 0)
        }
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

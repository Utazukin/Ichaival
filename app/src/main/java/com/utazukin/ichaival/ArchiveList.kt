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

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.SearchView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.content.IntentSanitizer
import androidx.core.view.isVisible
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.utazukin.ichaival.database.DatabaseReader
import com.utazukin.ichaival.database.SearchViewModel
import com.utazukin.ichaival.settings.SettingsActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

const val COVER_TRANSITION = "cover"

class ArchiveList : BaseActivity(), SharedPreferences.OnSharedPreferenceChangeListener, FilterListener {
    private lateinit var setupText: TextView
    private lateinit var categoryView: NavigationView

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

    override fun handleBackPressed() {
        if (drawerLayout.isDrawerOpen(categoryView))
            drawerLayout.closeDrawer(categoryView)
        else
            super.handleBackPressed()
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

    override fun onSaveInstanceState(outState: Bundle) {
        if (!outState.getBoolean(REFRESH_KEY))
            super.onSaveInstanceState(outState)
        else
            outState.remove(REFRESH_KEY)
    }

    override fun onSharedPreferenceChanged(pref: SharedPreferences, key: String?) {
        when (key) {
            getString(R.string.server_address_preference) -> {
                val location = pref.getString(key, "") as String
                intent.putExtra(REFRESH_KEY, true)
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
        drawerLayout.setStatusBarBackgroundColor(MaterialColors.getColor(drawerLayout, R.attr.colorSurface))
        categoryView = drawerLayout.findViewById(R.id.category_filter_view)
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings -> {
                    startSettingsActivity()
                    drawerLayout.closeDrawers()
                    true
                }
                R.id.nav_downloads -> {
                    val intent = Intent(this, DownloadsActivity::class.java)
                    startActivity(intent)
                    drawerLayout.closeDrawers()
                    true
                }
                else -> false
            }
        }
    }

    override fun onLongPressTab(tab: ReaderTab): Boolean {
        val tagFragment = TagDialogFragment.newInstance(tab.id)
        tagFragment.setTagPressListener { tag ->
            val searchView: SearchView = findViewById(R.id.archive_search)
            searchView.setQuery(tag, true)
            drawerLayout.closeDrawers()
        }

        tagFragment.setTagLongPressListener { tag ->
            val searchView: SearchView = findViewById(R.id.archive_search)
            searchView.setQuery("${searchView.query} $tag", true)
            drawerLayout.closeDrawers()
            true
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

    override fun onServerInitialized(serverSupported: InfoResult) {
        super.onServerInitialized(serverSupported)
        ServerManager.serverName?.let { supportActionBar?.title = it }
        when (serverSupported) {
            SupportedResult -> {
                launch {
                    val viewModel: SearchViewModel by viewModels()
                    if (DatabaseReader.needsUpdate(applicationContext)) {
                        val view = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                        val syncMessage = Snackbar.make(view, R.string.sync_snack_message, Snackbar.LENGTH_INDEFINITE)
                        syncMessage.show()
                        viewModel.viewModelScope.async { DatabaseReader.updateArchiveList(applicationContext) }.await()
                        syncMessage.dismiss()
                    }
                    viewModel.init()
                }
            }
            UnsupportedResult -> {
                setupText.text = getString(R.string.unsupported_server_message)
                handleSetupText(true)
            }
            else -> {
                val builder = MaterialAlertDialogBuilder(this).apply {
                    setTitle(R.string.connect_error_modal_title)
                    if (!WebHandler.verboseMessages)
                        setMessage(R.string.connect_error_modal_message)
                    else if (serverSupported is UnsuccessfulResult)
                        setMessage(getString(R.string.connect_error_modal_message) + "\n${serverSupported.code}")
                    else if (serverSupported is ExceptionResult)
                        setMessage(serverSupported.exception.localizedMessage)
                    setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
                }
                builder.show()
            }
        }
    }

    override fun onCategoryChanged(category: ArchiveCategory?) {
        val listView: RecyclerView = findViewById(R.id.list)
        (listView.adapter as? ArchiveRecyclerViewAdapter)?.disableMultiSelect()

        val searchView: SearchView = findViewById(R.id.archive_search)
        val viewModel: SearchViewModel by viewModels()
        if (category == null) {
            if (viewModel.categoryId.isEmpty()) {
                if (viewModel.filter.contentEquals(searchView.query)) {
                    searchView.setQuery("", false)
                    searchView.clearFocus()
                }

                viewModel.deferReset {
                    filter("")
                    categoryId = ""
                }
            } else viewModel.categoryId = ""
        }
        else if (category.isStatic) {
            viewModel.categoryId = category.id
        } else {
            viewModel.categoryId = ""
            searchView.setQuery(category.search, true)
            searchView.clearFocus()
        }
    }

    private fun handleSetupText(setup: Boolean) {
        if (setup) {
            setupText.setOnClickListener { startSettingsActivity() }
            setupText.isVisible = true
        } else
            setupText.visibility = View.GONE
    }

    private fun startSettingsActivity() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()

        val filter = IntentSanitizer.Builder()
            .allowExtra(REFRESH_KEY) { it is Boolean }
            .allowComponentWithPackage(packageName)
            .build()
        val intent = filter.sanitizeByFiltering(intent)
        if (intent.getBooleanExtra(REFRESH_KEY, false)) {
            finish()
            startActivity(intent)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                overridePendingTransition(0, 0)
            else {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ReaderTabHolder.registerAddListener(this)
        launch { ServerManager.generateTagSuggestions() }
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

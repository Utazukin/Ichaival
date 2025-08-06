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

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import com.utazukin.ichaival.database.SearchViewModel

const val RANDOM_SEARCH = "random"
const val RANDOM_CAT = "category"

// Refresh button UI constants
private const val LEFT_BUTTON_TAG = "refresh_button_left"
private const val TOOLBAR_BUTTON_MARGIN_DP = 56

class ArchiveRandomActivity : BaseActivity() {
    private val viewModel: SearchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_archive_search)
        setSupportActionBar(findViewById(R.id.toolbar))
        title = getString(R.string.random)
        supportActionBar?.run {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_arrow_back_white_24dp)
        }

        with(intent) {
            viewModel.deferReset {
                val filter = getStringExtra(RANDOM_SEARCH)
                val category = getStringExtra(RANDOM_CAT) ?: ""
                filter(filter)
                categoryId = category
                if (randomCount == 0) {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(this@ArchiveRandomActivity)
                    randomCount = prefs.castStringPrefToInt(getString(R.string.random_count_pref), 5)
                }
                init()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.archive_random_menu, menu)
        setupRefreshButtonPosition(menu)
        return super.onCreateOptionsMenu(menu)
    }

    /**
     * Configures the refresh button position based on user preference
     */
    private fun setupRefreshButtonPosition(menu: Menu) {
        val refreshPosition = getRefreshButtonPosition()
        val leftPositionValue = getString(R.string.refresh_position_left_value)
        
        when (refreshPosition) {
            leftPositionValue -> {
                // Hide menu item and add custom button on left
                menu.findItem(R.id.random_archive_refresh)?.isVisible = false
                addLeftRefreshButton()
            }
            else -> {
                // Keep standard menu item, remove any custom button
                removeLeftRefreshButton()
            }
        }
    }

    /**
     * Gets the current refresh button position preference
     */
    private fun getRefreshButtonPosition(): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val defaultValue = getString(R.string.refresh_position_right_value)
        return prefs.getString(getString(R.string.random_refresh_position_pref), defaultValue) ?: defaultValue
    }
    
    /**
     * Adds a refresh button to the left side of the toolbar
     */
    private fun addLeftRefreshButton() {
        removeLeftRefreshButton() // Ensure no duplicates
        
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar?.let { 
            val refreshButton = createRefreshButton()
            val layoutParams = createLeftButtonLayoutParams()
            toolbar.addView(refreshButton, layoutParams)
        }
    }

    /**
     * Removes any existing left refresh button from the toolbar
     */
    private fun removeLeftRefreshButton() {
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar?.findViewWithTag<android.widget.ImageButton>(LEFT_BUTTON_TAG)?.let { button ->
            toolbar.removeView(button)
        }
    }

    /**
     * Creates a properly configured refresh button
     */
    private fun createRefreshButton(): android.widget.ImageButton {
        return android.widget.ImageButton(this).apply {
            tag = LEFT_BUTTON_TAG
            setImageResource(R.drawable.ic_refresh_white_24dp)
            background = null
            contentDescription = getString(R.string.refresh_archive_menu)
            setOnClickListener { onRefreshClicked() }
        }
    }

    /**
     * Creates layout parameters for positioning the left button correctly
     */
    private fun createLeftButtonLayoutParams(): androidx.appcompat.widget.Toolbar.LayoutParams {
        return androidx.appcompat.widget.Toolbar.LayoutParams(
            androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT,
            androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            marginStart = (TOOLBAR_BUTTON_MARGIN_DP * resources.displayMetrics.density).toInt()
        }
    }

    /**
     * Handles refresh button click - extracted for reusability
     */
    private fun onRefreshClicked() {
        viewModel.reset()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.random_archive_refresh -> onRefreshClicked()
            R.id.change_random_count -> {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                val builder = AlertDialog.Builder(this).apply {
                    setTitle(getString(R.string.random_count_dialog_title))
                    val textField = EditText(this@ArchiveRandomActivity)
                    textField.inputType = InputType.TYPE_CLASS_NUMBER
                    val count = if (viewModel.randomCount == 0) prefs.getString(getString(R.string.random_count_pref), "5") else viewModel.randomCount.toString()
                    textField.setText(count)
                    setView(textField)
                    setPositiveButton(android.R.string.ok) { dialog, _ ->
                        viewModel.randomCount = textField.text.toString().toInt()
                        dialog.dismiss()
                    }

                    setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
                }
                val dialog = builder.create()
                dialog.show()
            }
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onTabInteraction(tab: ReaderTab) {
        super.onTabInteraction(tab)
        setResult(Activity.RESULT_OK)
        supportFinishAfterTransition()
    }

    override fun onStart() {
        super.onStart()
        ReaderTabHolder.registerAddListener(this)
    }

    override fun onStop() {
        super.onStop()
        ReaderTabHolder.unregisterAddListener(this)
    }

    override fun onDestroy() {
        // Clean up any custom toolbar buttons to prevent memory leaks
        removeLeftRefreshButton()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            val view = currentFocus
            if (view is EditText) {
                val outRect = Rect()
                view.getLocalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    view.clearFocus()
                    with(getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager) {
                        hideSoftInputFromWindow(view.windowToken, 0)
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}

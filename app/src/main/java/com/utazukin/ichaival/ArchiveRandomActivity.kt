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
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.random_archive_refresh -> viewModel.reset()
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

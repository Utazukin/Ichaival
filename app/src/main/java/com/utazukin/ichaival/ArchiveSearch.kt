/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2023 Utazukin
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
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.viewModels
import androidx.core.app.ActivityOptionsCompat
import androidx.core.util.Pair
import com.utazukin.ichaival.ArchiveListFragment.OnListFragmentInteractionListener
import com.utazukin.ichaival.database.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ArchiveSearch : BaseActivity(), OnListFragmentInteractionListener {

    override fun onListFragmentInteraction(archive: ArchiveBase?, view: View) {
        if (archive != null) {
            setResult(Activity.RESULT_OK)
            startDetailsActivity(archive.id, view)
        }
    }

    private fun startDetailsActivity(id: String, view: View) {
        val intent = Intent(this, ArchiveDetails::class.java)
        val bundle = Bundle()
        bundle.putString("id", id)
        intent.putExtras(bundle)
        addIntentFlags(intent, id)
        val coverView: View = view.findViewById(R.id.archive_thumb)
        val statusBar: View = findViewById(android.R.id.statusBarBackground)
        val coverPair = Pair(coverView, COVER_TRANSITION)
        val statusPair = Pair(statusBar, Window.STATUS_BAR_BACKGROUND_TRANSITION_NAME)
        startActivity(intent, ActivityOptionsCompat.makeSceneTransitionAnimation(this, coverPair, statusPair).toBundle())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_archive_search)
        setSupportActionBar(findViewById(R.id.toolbar))
        val tag = intent.getStringExtra(TAG_SEARCH)
        val viewModel: SearchViewModel by viewModels()
        with(viewModel) {
            isSearch = true
            filter(tag)
            init()
        }
    }

    override fun onTabInteraction(tab: ReaderTab) {
        super.onTabInteraction(tab)
        setResult(Activity.RESULT_OK)
        finish()
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

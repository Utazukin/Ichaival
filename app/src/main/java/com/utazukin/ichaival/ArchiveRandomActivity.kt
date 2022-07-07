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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityOptionsCompat
import androidx.preference.PreferenceManager
import com.utazukin.ichaival.ArchiveListFragment.OnListFragmentInteractionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

const val RANDOM_SEARCH = "random"
const val RANDOM_CAT = "category"

class ArchiveRandomActivity : BaseActivity(), OnListFragmentInteractionListener {

    override fun onListFragmentInteraction(archive: Archive?, view: View) {
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
        startActivity(intent, ActivityOptionsCompat.makeSceneTransitionAnimation(this, coverView, COVER_TRANSITION).toBundle())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_archive_search)
        setSupportActionBar(findViewById(R.id.toolbar))
        title = getString(R.string.random)
        supportActionBar?.run {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_arrow_back_white_24dp)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.archive_random_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.random_archive_refresh ->  {
                val listFragment = supportFragmentManager.findFragmentById(R.id.list_fragment) as? ArchiveListFragment
                listFragment?.setupRandomList()
            }
            R.id.change_random_count -> {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                val builder = AlertDialog.Builder(this).apply {
                    setTitle(getString(R.string.random_count_dialog_title))
                    val textField = EditText(this@ArchiveRandomActivity)
                    textField.inputType = InputType.TYPE_CLASS_NUMBER
                    textField.setText(prefs.getString(getString(R.string.random_count_pref), "5"))
                    setView(textField)
                    setPositiveButton(android.R.string.ok) { dialog, _ ->
                        val listFragment = supportFragmentManager.findFragmentById(R.id.list_fragment) as? ArchiveListFragment
                        listFragment?.setupRandomList(textField.text.toString().toInt())
                        dialog.dismiss()
                    }

                    setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
                }
                val dialog = builder.create()
                dialog.show()
            }
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onServerInitialized() {
        super.onServerInitialized()
        val listFragment = supportFragmentManager.findFragmentById(R.id.list_fragment) as? ArchiveListFragment
        listFragment?.setupRandomList()
    }

    override fun onTabInteraction(tab: ReaderTab) {
        super.onTabInteraction(tab)
        setResult(Activity.RESULT_OK)
        supportFinishAfterTransition()
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

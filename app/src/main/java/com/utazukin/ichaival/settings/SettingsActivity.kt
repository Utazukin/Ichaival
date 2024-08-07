/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2024 Utazukin
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

package com.utazukin.ichaival.settings

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NavUtils
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.utazukin.ichaival.R
import com.utazukin.ichaival.WebHandler
import com.utazukin.ichaival.database.DatabaseMessageListener
import com.utazukin.ichaival.getCustomTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity(), DatabaseMessageListener, CoroutineScope, PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    override val coroutineContext = lifecycleScope.coroutineContext

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme()
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null)
            supportFragmentManager.beginTransaction().replace(android.R.id.content, SettingsFragment()).commit()

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val args = pref.extras
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, pref.fragment!!).apply {
            arguments = args
        }

        supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).addToBackStack(null).commit()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        if (id == android.R.id.home) {
            if (!super.onOptionsItemSelected(item)) {
                NavUtils.navigateUpFromSameTask(this)
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setTheme() {
        when (getCustomTheme()) {
            getString(R.string.dark_theme) -> setTheme(R.style.SettingsTheme)
            getString(R.string.black_theme) -> setTheme(R.style.SettingsTheme_Black)
            getString(R.string.material_theme) -> setTheme(R.style.SettingsTheme_MaterialYou)
        }
    }

    override fun onStart() {
        super.onStart()
        WebHandler.listener = this
    }

    override fun onPause() {
        super.onPause()
        WebHandler.listener = null
    }

    override fun onError(error: String) {
        launch { Toast.makeText(this@SettingsActivity, getString(R.string.error_message, error), Toast.LENGTH_LONG).show() }
    }

    override fun onInfo(message: String) {
        launch { Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show() }
    }

}

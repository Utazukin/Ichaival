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

package com.utazukin.ichaival.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ShareCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.EditTextPreference.OnBindEditTextListener
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import coil3.imageLoader
import com.utazukin.ichaival.R
import com.utazukin.ichaival.ServerManager
import com.utazukin.ichaival.WebHandler
import com.utazukin.ichaival.clearDiskCache
import com.utazukin.ichaival.database.DatabaseReader
import com.utazukin.ichaival.diskCacheSize
import com.utazukin.ichaival.reader.DualPageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LongClickPreference
    @JvmOverloads constructor(context: Context, attributeSet: AttributeSet? = null) : Preference(context, attributeSet) {
    var longClickListener: View.OnLongClickListener? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        holder.itemView.setOnLongClickListener(longClickListener)
    }
}

class SettingsFragment : PreferenceFragmentCompat(), MenuProvider, CoroutineScope {
    override val coroutineContext = lifecycleScope.coroutineContext

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_general, rootKey)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        with(requireActivity() as MenuHost) {
            addMenuProvider(this@SettingsFragment, viewLifecycleOwner, Lifecycle.State.RESUMED)
        }
        return super.onCreateView(inflater, container, savedInstanceState).apply { fitsSystemWindows = true }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {}
    override fun onMenuItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        if (id == android.R.id.home) {
            startActivity(Intent(activity, SettingsActivity::class.java))
            return true
        }
        return false
    }

    private fun onBindEditText(inputType: Int): OnBindEditTextListener {
        return OnBindEditTextListener {
            it.inputType = inputType
            it.setSelection(it.text.length)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pref: Preference? = findPreference(getString(R.string.server_address_preference))
        bindPrefSummaryNotify(pref)

        val apiPref: Preference? = findPreference(getString(R.string.api_key_pref))
        bindPreferenceSummaryToValue(apiPref)

        val themePref: ListPreference? = findPreference(getString(R.string.theme_pref))
        bindPreferenceSummaryToValue(themePref)
        themePref?.run {
            setOnPreferenceChangeListener { _, _ ->
                requireActivity().recreate()
                true
            }

            //Remove the Material You option for versions before 12.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                val materialTheme = getString(R.string.material_theme)
                entries = entries.filterNot { it == materialTheme }.toTypedArray()
                entryValues = entryValues.filterNot { it == materialTheme  }.toTypedArray()
            }
        }

        val listTypePref: Preference? = findPreference(getString(R.string.archive_list_type_key))
        bindPreferenceSummaryToValue(listTypePref)

        val cachePref: Preference? = findPreference(getString(R.string.local_cache_pref))
        cachePref?.let { setupCachePref(it) }

        val compressPref: Preference? = findPreference(getString(R.string.compression_type_pref))
        bindPreferenceSummaryToValue(compressPref)

        findPreference<Preference>(getString(R.string.scale_type_pref))?.let { bindPreferenceSummaryToValue(it) }
        findPreference<Preference>(getString(R.string.reader_bg_pref_key))?.let { bindPreferenceSummaryToValue(it) }

        findPreference<EditTextPreference>(getString(R.string.random_count_pref))?.let {
            it.setOnBindEditTextListener(onBindEditText(InputType.TYPE_CLASS_NUMBER))
            bindPreferenceSummaryFormat(it)
        }

        findPreference<EditTextPreference>(getString(R.string.search_delay_key))?.let {
            it.setOnBindEditTextListener(onBindEditText(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED))
            bindPreferenceSummaryFormat(it)
        }

        findPreference<EditTextPreference>(getString(R.string.fullscreen_timeout_key))?.let {
            it.setOnBindEditTextListener(onBindEditText(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED))
            bindPreferenceSummaryFormat(it)
        }

        findPreference<EditTextPreference>(getString(R.string.sync_time_key))?.let {
            it.setOnBindEditTextListener(onBindEditText(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED))
            bindPreferenceSummaryFormat(it)
        }

        val licensePref: Preference? = findPreference(getString(R.string.license_key))
        licensePref?.setOnPreferenceClickListener {
            startWebActivity("file:////android_asset/licenses.html")
            true
        }

        val gplPref: Preference? = findPreference(getString(R.string.gpl_key))
        gplPref?.setOnPreferenceClickListener {
            startWebActivity("file:////android_asset/license.txt")
            true
        }

        val gitPref: Preference? = findPreference(getString(R.string.git_key))
        gitPref?.setOnPreferenceClickListener {
            val webpage = Uri.parse("https://github.com/Utazukin/Ichaival")
            val intent = Intent(Intent.ACTION_VIEW, webpage)
            startActivity(intent)
            true
        }

        val thumbPref: Preference? = findPreference(getString(R.string.thumbnail_pref))
        thumbPref?.setOnPreferenceClickListener {
            DatabaseReader.clearThumbnails(requireActivity())
            Toast.makeText(activity, getString(R.string.clear_cache), Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<Preference>(getString(R.string.temp_folder_pref))?.run {
            if (ServerManager.canEdit) {
                setOnPreferenceClickListener {
                    launch {
                        launch { WebHandler.clearTempFolder(requireContext()) }
                        DatabaseReader.invalidateImageCache()
                        with(requireContext().imageLoader) {
                            memoryCache?.clear()
                            clearDiskCache()
                        }
                        DualPageHelper.clearMergedPages(requireContext().cacheDir)
                        cachePref?.summary = "0 MB"
                    }
                    true
                }
            }
            else
                isVisible = false
        }

        val saveLogPref: LongClickPreference? = findPreference(getString(R.string.log_save_pref))
        saveLogPref?.let {
            val logFile = File(context?.noBackupFilesDir, "crash.log")
            val exists = logFile.exists()
            it.isVisible = exists
            it.setOnPreferenceClickListener {
                ShareCompat.IntentBuilder(requireContext())
                    .setText(logFile.readText())
                    .setType("text/plain")
                    .setSubject(getString(R.string.crash_log_subject))
                    .setChooserTitle(getString(R.string.copy_log_title))
                    .startChooser()
                true
            }

            it.longClickListener = View.OnLongClickListener { _ ->
                if (exists) {
                    logFile.delete()
                    Toast.makeText(context, getString(R.string.log_delete_message), Toast.LENGTH_SHORT).show()
                    it.isVisible = false
                    true
                } else false
            }
        }
    }

    private fun setupCachePref(cachePref: Preference) {
        cachePref.setOnPreferenceClickListener {
            launch(Dispatchers.IO) {
                DatabaseReader.invalidateImageCache()
                DualPageHelper.clearMergedPages(requireContext().cacheDir)
                with(requireContext().imageLoader) {
                    clearDiskCache()
                    memoryCache?.clear()
                }
            }
            cachePref.summary = "0 MB"
            true
        }

        launch(Dispatchers.IO) {
            var size = DualPageHelper.getCacheSize(requireContext().cacheDir)
            size += requireContext().imageLoader.diskCacheSize
            size = size / 1024 / 1024
            withContext(Dispatchers.Main) { cachePref.summary = "$size MB" }
        }
    }

    private fun startWebActivity(url: String) {
        val intent = Intent(activity, WebViewActivity::class.java)
        val bundle = Bundle().apply { putString(URL_KEY, url) }
        intent.putExtras(bundle)
        startActivity(intent)
    }

    companion object {

        /**
         * A preference value change listener that updates the preference's summary
         * to reflect its new value.
         */
        private val sBindPreferenceSummaryToValueListener = Preference.OnPreferenceChangeListener { preference, value ->
            val stringValue = value.toString()

            if (preference is ListPreference) {
                // For list preferences, look up the correct display value in
                // the preference's 'entries' list.
                val index = preference.findIndexOfValue(stringValue)

                // Set the summary to reflect the new value.
                preference.setSummary(
                    if (index >= 0)
                        preference.entries[index]
                    else
                        null
                )
            } else {
                // For all other preferences, set the summary to the value's
                // simple string representation.
                preference.summary = stringValue
            }
            true
        }

        private fun getPreferenceSummaryFormatListener(pref: Preference) : Preference.OnPreferenceChangeListener {
            val formatString = pref.summary?.toString()
            return Preference.OnPreferenceChangeListener { preference, value ->
                val stringValue = value.toString()

                if (preference is ListPreference) {
                    // For list preferences, look up the correct display value in
                    // the preference's 'entries' list.
                    val index = preference.findIndexOfValue(stringValue)

                    // Set the summary to reflect the new value.
                    preference.setSummary(formatString?.format(
                        if (index >= 0)
                            preference.entries[index]
                        else
                            null
                    ))
                } else {
                    // For all other preferences, set the summary to the value's
                    // simple string representation.
                    preference.summary = formatString?.format(stringValue)
                }
                true
            }
        }

        private val bindAndNotifyPreferenceListener = Preference.OnPreferenceChangeListener { pref, value ->
            sBindPreferenceSummaryToValueListener.onPreferenceChange(pref, WebHandler.onPreferenceChange(value as? String))
        }

        /**
         * Binds a preference's summary to its value. More specifically, when the
         * preference's value is changed, its summary (line of text below the
         * preference title) is updated to reflect the value. The summary is also
         * immediately updated upon calling this method. The exact display format is
         * dependent on the type of preference.

         * @see .sBindPreferenceSummaryToValueListener
         */
        private fun bindPreferenceSummaryToValue(preference: Preference?) {
            preference?.let {
                // Set the listener to watch for value changes.
                it.onPreferenceChangeListener = sBindPreferenceSummaryToValueListener

                // Trigger the listener immediately with the preference's
                // current value.
                sBindPreferenceSummaryToValueListener.onPreferenceChange(
                    it,
                    PreferenceManager
                        .getDefaultSharedPreferences(it.context)
                        .getString(it.key, "")
                )
            }
        }

        private fun bindPreferenceSummaryFormat(preference: Preference?) {
            preference?.let {
                val listener = getPreferenceSummaryFormatListener(it)
                it.onPreferenceChangeListener = listener
                listener.onPreferenceChange(it, PreferenceManager.getDefaultSharedPreferences(it.context).getString(it.key, ""))
            }
        }

        private fun bindPrefSummaryNotify(preference: Preference?) {
            preference?.let {
                it.onPreferenceChangeListener = bindAndNotifyPreferenceListener
                bindAndNotifyPreferenceListener.onPreferenceChange(
                    it,
                    PreferenceManager
                        .getDefaultSharedPreferences(it.context)
                        .getString(it.key, "")
                )
            }
        }
    }
}
/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2019 Utazukin
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
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.*
import android.widget.Button
import android.widget.CheckBox
import android.widget.SearchView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor

class ArchiveListFragment : Fragment(), DatabaseRefreshListener {

    private var sortMethod = SortMethod.Alpha
    private var descending = false
    private var sortUpdated = false
    private var listener: OnListFragmentInteractionListener? = null
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var listView: RecyclerView
    private lateinit var activityScope: CoroutineScope
    private lateinit var newCheckBox: CheckBox
    private lateinit var randomButton: Button
    private lateinit var viewModel: ArchiveViewModel
    lateinit var searchView: SearchView
        private set

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_archive_list, container, false)
        listView = view.findViewById(R.id.list)
        lateinit var listAdapter: ArchiveRecyclerViewAdapter
        setHasOptionsMenu(true)
        viewModel = ViewModelProviders.of(this).get(ArchiveViewModel::class.java)

        // Set the adapter
        with(listView) {
            post {
                val dpWidth = getDpWidth(width)
                val columns = floor(dpWidth / 300.0).toInt()
                layoutManager = if (columns > 1) {
                    object : GridLayoutManager(context, columns) {
                        override fun onLayoutCompleted(state: RecyclerView.State?) {
                            super.onLayoutCompleted(state)
                            if (sortUpdated) {
                                scrollToPosition(0)
                                sortUpdated = false
                            }
                        }
                    }
                } else {
                    object : LinearLayoutManager(context) {
                        override fun onLayoutCompleted(state: RecyclerView.State?) {
                            super.onLayoutCompleted(state)
                            if (sortUpdated) {
                                scrollToPosition(0)
                                sortUpdated = false
                            }
                        }
                    }
                }
            }
            val temp = ArchiveRecyclerViewAdapter(listener, ::handleArchiveLongPress, activityScope, Glide.with(context))
            listAdapter = temp
            adapter = temp
        }

        searchView = view.findViewById(R.id.archive_search)
        newCheckBox = view.findViewById(R.id.new_checkbox)
        newCheckBox.setOnCheckedChangeListener { _, checked -> viewModel.filter(searchView.query, checked) }

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(p0: String?): Boolean {
                viewModel.filter(p0, newCheckBox.isChecked)
                return true
            }

            override fun onQueryTextChange(p0: String?): Boolean {
                viewModel.filter(p0, newCheckBox.isChecked)
                return true
            }
        })
        searchView.clearFocus()

        randomButton = view.findViewById(R.id.random_button)
        randomButton.setOnClickListener { v ->
            activityScope.launch {
                val archive = withContext(Dispatchers.IO) { viewModel.getRandom() }
                if (archive != null)
                    startDetailsActivity(archive.id, v?.context)
            }
        }

        randomButton.setOnLongClickListener {
            activityScope.launch {
                val archive = withContext(Dispatchers.IO) { viewModel.getRandom() }
                if (archive != null && !ReaderTabHolder.isTabbed(archive.id))
                    ReaderTabHolder.addTab(archive, 0)
            }
            true
        }

        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh)
        swipeRefreshLayout.setOnRefreshListener { forceArchiveListUpdate() }

        DatabaseReader.init(activity!!.applicationContext)
        activityScope.launch(Dispatchers.Main) {
            withContext(Dispatchers.Default) { DatabaseReader.updateArchiveList(context!!.filesDir) }

            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            val method = SortMethod.fromInt(prefs.getInt(getString(R.string.sort_pref), 1)) ?: SortMethod.Alpha

            val descending = prefs.getBoolean(getString(R.string.desc_pref), false)
            viewModel.init(DatabaseReader.database.archiveDao(), method, descending, searchView.query, newCheckBox.isChecked)
            viewModel.archiveList.observe(this@ArchiveListFragment, Observer {
                listAdapter.submitList(it)
                val size = it.size
                (activity as AppCompatActivity).supportActionBar?.subtitle = resources.getQuantityString(R.plurals.archive_count, size, size)
            })
            updateSortMethod(method, descending, prefs)
            viewModel.filter(searchView.query, newCheckBox.isChecked)
        }
        return view
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.archive_list_menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.sort_menu -> {
                fragmentManager?.let {
                    val sortDialogFragment = SortDialogFragment.createInstance(sortMethod, descending)
                    val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
                    sortDialogFragment.setSortChangeListener { method, desc -> updateSortMethod(method, desc, prefs) }
                    sortDialogFragment.show(it, "sort_popup")
                    true
                } ?: false
            }
            else -> false
        }
    }

    private fun handleArchiveLongPress(archive: Archive) : Boolean {
        fragmentManager?.let {
            val tagFragment = TagDialogFragment.newInstance(archive.id)
            tagFragment.setTagPressListener { tag -> searchView.setQuery(tag, true) }
            tagFragment.setTagLongPressListener { tag ->
                searchView.setQuery("${searchView.query} $tag", true)
                true
            }
            tagFragment.show(it, "tag_popup")
        }
        return true
    }

    private fun updateSortMethod(method: SortMethod, desc: Boolean, prefs: SharedPreferences) {
        var updated = false
        if (sortMethod != method) {
            sortMethod = method

            prefs.edit().putInt(getString(R.string.sort_pref), sortMethod.value).apply()
            updated = true
        }

        if (desc != descending) {
            descending = desc
            prefs.edit().putBoolean(getString(R.string.desc_pref), descending).apply()
            updated = true
        }

        if (updated) {
            viewModel.updateSort(method, descending)
            sortUpdated = true
        }
    }

    fun showOnlySearch(show: Boolean){
        if (show) {
            randomButton.visibility = View.GONE
            newCheckBox.visibility = View.GONE
            swipeRefreshLayout.isEnabled = false
        } else {
            randomButton.visibility = View.VISIBLE
            newCheckBox.visibility = View.VISIBLE
            swipeRefreshLayout.isEnabled = true
        }
    }

    private fun startDetailsActivity(id: String, context:Context?){
        val intent = Intent(context, ArchiveDetails::class.java)
        val bundle = Bundle()
        bundle.putString("id", id)
        intent.putExtras(bundle)
        startActivity(intent)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        DatabaseReader.refreshListener = this
        if (context is OnListFragmentInteractionListener) {
            listener = context
            activityScope = context as CoroutineScope
        } else {
            throw RuntimeException("$context must implement OnListFragmentInteractionListener")
        }
    }

    private fun setupTagList(tagHolder: TagListHolder?) {
        tagHolder?.run {
            val tagAdapter = TagSuggestionViewAdapter { tag, add ->
                if (add)
                    Toast.makeText(context, "Added $tag", Toast.LENGTH_SHORT).show()

                searchView.setQuery(if (add) "${searchView.query} \"$tag\"" else "\"$tag\"", true)
            }
            setupTagList(tagAdapter)
        }
    }

    override fun onStart() {
        super.onStart()
        setupTagList(context as? TagListHolder)
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
        DatabaseReader.refreshListener = null
    }

    fun forceArchiveListUpdate() {
        activityScope.launch {
            withContext(Dispatchers.Default) { DatabaseReader.updateArchiveList(context!!.filesDir, true) }
            viewModel.filter(searchView.query, newCheckBox.isChecked)
        }
    }

    override fun isRefreshing(refreshing: Boolean) {
        activityScope.launch { swipeRefreshLayout.isRefreshing = refreshing }
    }

    interface OnListFragmentInteractionListener {
        fun onListFragmentInteraction(archive: Archive?)
    }
}

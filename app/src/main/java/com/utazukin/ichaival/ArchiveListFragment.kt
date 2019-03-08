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
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.*
import android.widget.Button
import android.widget.CheckBox
import android.widget.SearchView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArchiveListFragment : Fragment() {

    private var sortMethod = SortMethod.Alpha
    private var listener: OnListFragmentInteractionListener? = null
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var listView: RecyclerView
    private lateinit var activityScope: CoroutineScope
    private lateinit var newCheckBox: CheckBox
    private lateinit var randomButton: Button
    private lateinit var countText: TextView
    lateinit var searchView: SearchView
        private set

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_archive_list, container, false)
        listView = view.findViewById(R.id.list)
        lateinit var listAdapter: ArchiveRecyclerViewAdapter
        countText = view.findViewById(R.id.list_count)
        setHasOptionsMenu(true)

        // Set the adapter
        with(listView) {
            post {
                val dpWidth = getDpWidth(width)
                val columns = Math.floor(dpWidth / 300.0).toInt()
                layoutManager = if (columns > 1) GridLayoutManager(
                    context,
                    columns
                ) else LinearLayoutManager(context)
            }
            val temp = ArchiveRecyclerViewAdapter(listener, ::handleArchiveLongPress, activityScope)
            listAdapter = temp
            adapter = temp

            addOnScrollListener(object: RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    val hideCount = listView.canScrollVertically(-1)
                    countText.visibility = if (hideCount) View.GONE else View.VISIBLE
                }
            })
        }

        searchView = view.findViewById(R.id.archive_search)
        newCheckBox = view.findViewById(R.id.new_checkbox)
        newCheckBox.setOnCheckedChangeListener { _, checked ->
            val count = listAdapter.filter(searchView.query, checked)
            countText.text = resources.getQuantityString(R.plurals.archive_count, count, count)
        }

        countText.visibility = View.INVISIBLE
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(p0: String?): Boolean {
                val count = listAdapter.filter(p0, newCheckBox.isChecked)
                countText.text = resources.getQuantityString(R.plurals.archive_count, count, count)
                return true
            }

            override fun onQueryTextChange(p0: String?): Boolean {
                val count = listAdapter.filter(p0, newCheckBox.isChecked)
                countText.text = resources.getQuantityString(R.plurals.archive_count, count, count)
                return true
            }
        })
        searchView.clearFocus()

        randomButton = view.findViewById(R.id.random_button)
        randomButton.setOnClickListener { v ->
            val archive = listAdapter.getRandomArchive()
            if (archive != null)
                startDetailsActivity(archive.id, v?.context)
        }

        randomButton.setOnLongClickListener {
            val archive = listAdapter.getRandomArchive()
            if (archive != null && !ReaderTabHolder.isTabbed(archive.id))
                ReaderTabHolder.addTab(archive, 0)
            true
        }

        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh)
        swipeRefreshLayout.setOnRefreshListener { forceArchiveListUpdate() }

        activityScope.launch(Dispatchers.Main) {
            val updatedList = withContext(Dispatchers.Default) { DatabaseReader.readArchiveList(context!!.filesDir) }
            listAdapter.updateDataCopy(updatedList)

            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            val method = SortMethod.fromInt(prefs.getInt(getString(R.string.sort_pref), 1)) ?: SortMethod.Alpha
            updateSortMethod(method)

            val count = listAdapter.filter(searchView.query, newCheckBox.isChecked)
            countText.text = resources.getQuantityString(R.plurals.archive_count, count, count)
            countText.visibility = View.VISIBLE
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
                    val sortDialogFragment = SortDialogFragment.createInstance(sortMethod)
                    sortDialogFragment.setSortChangeListener(::updateSortMethod)
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

    private fun updateSortMethod(method: SortMethod) {
        if (sortMethod != method) {
            sortMethod = method

            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            prefs.edit().putInt(getString(R.string.sort_pref), sortMethod.value).apply()

            val listAdapter = listView.adapter as? ArchiveRecyclerViewAdapter
            listAdapter?.updateSort(method)
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
        if (context is OnListFragmentInteractionListener) {
            listener = context
            activityScope = context as CoroutineScope
        } else {
            throw RuntimeException("$context must implement OnListFragmentInteractionListener")
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    fun forceArchiveListUpdate() {
        activityScope.launch {
            val newList = withContext(Dispatchers.Default) { DatabaseReader.readArchiveList(context!!.filesDir, true) }
            val adapter = listView.adapter as ArchiveRecyclerViewAdapter
            adapter.updateDataCopy(newList)
            swipeRefreshLayout.isRefreshing = false
            val count = adapter.filter(searchView.query, newCheckBox.isChecked)
            countText.text = resources.getQuantityString(R.plurals.archive_count, count, count)
        }
    }

    interface OnListFragmentInteractionListener {
        fun onListFragmentInteraction(archive: Archive?)
    }
}

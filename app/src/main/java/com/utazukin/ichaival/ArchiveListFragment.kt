/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2020 Utazukin
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

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.provider.BaseColumns
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import kotlinx.coroutines.*
import kotlin.math.floor

private const val RESULTS_KEY = "search_results"
private const val RESULTS_SIZE_KEY = "search_size"
private const val DEFAULT_SEARCH_DELAY = 750L
private const val STATIC_CATEGORY_SEARCH = "\b"

class ArchiveListFragment : Fragment(), DatabaseRefreshListener, SharedPreferences.OnSharedPreferenceChangeListener {
    private var sortMethod = SortMethod.Alpha
    private var descending = false
    private var sortUpdated = false
    private var listener: OnListFragmentInteractionListener? = null
    private var searchJob: Job? = null
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var listView: RecyclerView
    private lateinit var newCheckBox: CheckBox
    private lateinit var randomButton: Button
    private var viewModel: SearchViewModelBase? = null
    lateinit var searchView: SearchView
        private set
    private var searchDelay: Long = DEFAULT_SEARCH_DELAY
    private var isLocalSearch: Boolean = false
    private var creatingView = false
    private var savedState: Bundle? = null


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_archive_list, container, false)
        listView = view.findViewById(R.id.list)
        lateinit var listAdapter: ArchiveRecyclerViewAdapter
        setHasOptionsMenu(true)

        savedState = savedInstanceState
        creatingView = true
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        searchDelay = prefs.castStringPrefToLong(getString(R.string.search_delay_key), DEFAULT_SEARCH_DELAY)
        isLocalSearch = prefs.getBoolean(getString(R.string.local_search_key), false)

        if (isLocalSearch)
            viewModel = ViewModelProviders.of(this).get(ArchiveViewModel::class.java)
        else
            viewModel = ViewModelProviders.of(this).get(SearchViewModel::class.java)

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
            listAdapter = ArchiveRecyclerViewAdapter(listener, ::handleArchiveLongPress, viewLifecycleOwner.lifecycleScope, Glide.with(context))
            adapter = listAdapter
        }

        searchView = view.findViewById(R.id.archive_search)
        newCheckBox = view.findViewById(R.id.new_checkbox)
        newCheckBox.setOnCheckedChangeListener { _, checked ->
            if (viewModel is StaticCategoryModel)
                (viewModel as StaticCategoryModel).filter(checked)
            else if (isLocalSearch)
                getViewModel<ArchiveViewModel>().filter(searchView.query, checked)
            else {
                searchJob?.cancel()
                swipeRefreshLayout.isRefreshing = false
                if (checked || searchView.query.isNotBlank()) {
                    searchJob = lifecycleScope.launch {
                        val results = withContext(Dispatchers.Default) {
                            WebHandler.searchServer(searchView.query, checked, sortMethod, descending)
                        }
                        getViewModel<SearchViewModel>().filter(results)
                    }
                } else
                    getViewModel<SearchViewModel>().filter(ServerSearchResult(null))
            }
        }

        setupTagSuggestions()
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (isLocalSearch)
                    getViewModel<ArchiveViewModel>().filter(query, newCheckBox.isChecked)
                else {
                    searchJob?.cancel()
                    swipeRefreshLayout.isRefreshing = false
                    searchJob = lifecycleScope.launch {
                        if (query != null) {
                            val results = withContext(Dispatchers.Default) {
                                WebHandler.searchServer(query, newCheckBox.isChecked, sortMethod, descending)
                            }
                            getViewModel<SearchViewModel>().filter(results)
                        }
                    }
                }
                return true
            }

            override fun onQueryTextChange(query: String?): Boolean {
                handleSearchSuggestion(query)
                if (creatingView)
                    return true

                val categoryFragment: CategoryFilterFragment? = requireActivity().supportFragmentManager.findFragmentById(R.id.category_fragment) as CategoryFilterFragment?
                categoryFragment?.selectedCategory?.let {
                    if (it is StaticCategory && query == STATIC_CATEGORY_SEARCH)
                        return true
                    if (it is StaticCategory || (it is DynamicCategory && it.search != query))
                        categoryFragment.clearCategory()
                }

                if (query?.startsWith(STATIC_CATEGORY_SEARCH) == true) {
                    searchView.setQuery(query.removePrefix(STATIC_CATEGORY_SEARCH), false)
                    return false
                }

                if (isLocalSearch)
                    getViewModel<ArchiveViewModel>().filter(query, newCheckBox.isChecked)
                else {
                    searchJob?.cancel()
                    swipeRefreshLayout.isRefreshing = false
                    searchJob = lifecycleScope.launch {
                        if (!query.isNullOrBlank() || newCheckBox.isChecked)
                            delay(searchDelay)

                        if (query != null) {
                            val results = withContext(Dispatchers.Default) {
                                WebHandler.searchServer(query, newCheckBox.isChecked, sortMethod, descending)
                            }
                            getViewModel<SearchViewModel>().filter(results)
                        }
                    }
                }
                return true
            }
        })
        searchView.clearFocus()

        randomButton = view.findViewById(R.id.random_button)
        randomButton.setOnClickListener { v ->
            lifecycleScope.launch {
                val archive = withContext(Dispatchers.IO) { viewModel?.getRandom(false) }
                if (archive != null)
                    startDetailsActivity(archive.id, v?.context)
            }
        }

        randomButton.setOnLongClickListener {
            lifecycleScope.launch {
                val archive = withContext(Dispatchers.IO) { viewModel?.getRandom() }
                if (archive != null && !ReaderTabHolder.isTabbed(archive))
                    ReaderTabHolder.addTab(archive, 0)
            }
            true
        }

        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh)

        val canSwipeRefresh = prefs.getBoolean(getString(R.string.swipe_refresh_key), true)
        swipeRefreshLayout.setOnRefreshListener { forceArchiveListUpdate() }
        swipeRefreshLayout.isEnabled = canSwipeRefresh

        return view
    }

    fun setupArchiveList() {
        lifecycleScope.launch {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val listAdapter = listView.adapter as ArchiveRecyclerViewAdapter
            withContext(Dispatchers.IO) { DatabaseReader.updateArchiveList(requireContext().filesDir) }

            val method = SortMethod.fromInt(prefs.getInt(getString(R.string.sort_pref), 1)) ?: SortMethod.Alpha
            val descending = prefs.getBoolean(getString(R.string.desc_pref), false)

            if (isLocalSearch)
                getViewModel<ArchiveViewModel>(false).init(method, descending, searchView.query, newCheckBox.isChecked, activity is ArchiveSearch)
            else {
                getViewModel<SearchViewModel>(false).init(method, descending, isSearch = activity is ArchiveSearch)
            }

            when {
                isLocalSearch -> getViewModel<ArchiveViewModel>().filter(searchView.query, newCheckBox.isChecked)
                savedState != null -> {
                    savedState?.getStringArray(RESULTS_KEY)?.let {
                        val totalSize = savedState!!.getInt(RESULTS_SIZE_KEY)
                        val result = ServerSearchResult(it.asList(), totalSize, searchView.query, newCheckBox.isChecked)
                        getViewModel<SearchViewModel>().filter(result)
                    }
                }
                searchView.query != null -> {
                    searchView.query?. let {
                        val results = withContext(Dispatchers.IO) {
                            WebHandler.searchServer(it, newCheckBox.isChecked, sortMethod, descending)
                        }
                        getViewModel<SearchViewModel>().filter(results)
                    }
                }
            }
            viewModel?.archiveList?.observe(viewLifecycleOwner, Observer {
                listAdapter.submitList(it)
                val size = it.size
                (activity as AppCompatActivity).supportActionBar?.subtitle = resources.getQuantityString(R.plurals.archive_count, size, size)
            })
            updateSortMethod(method, descending, prefs)

            savedState = null
            creatingView = false
        }
    }

    fun handleCategoryChange(category: ArchiveCategory) {
        if (category is DynamicCategory) {
            searchView.setQuery(category.search, true)
            searchView.clearFocus()
        }
        else if (category is StaticCategory) {
            searchView.setQuery(STATIC_CATEGORY_SEARCH, false)
            searchView.clearFocus()

            val model = ViewModelProviders.of(this).get(StaticCategoryModel::class.java).apply {
                init(category.archiveIds, category.id, sortMethod, descending, newCheckBox.isChecked)
            }
            model.archiveList?.observe(viewLifecycleOwner, Observer {
                (listView.adapter as ArchiveRecyclerViewAdapter).submitList(it)
                val size = it.size
                (activity as AppCompatActivity).supportActionBar?.subtitle = resources.getQuantityString(R.plurals.archive_count, size, size)
            })

            model.filter(newCheckBox.isChecked)
            viewModel = model
        }
    }

    private fun setupTagSuggestions() {
        val from = arrayOf(SearchManager.SUGGEST_COLUMN_TEXT_1)
        val to = intArrayOf(R.id.search_suggestion)
        val adapter = SimpleCursorAdapter(context, R.layout.search_suggestion_layout, null, from, to, CursorAdapter.FLAG_REGISTER_CONTENT_OBSERVER)

        searchView.run {
            suggestionsAdapter = adapter

            setOnSuggestionListener(object: SearchView.OnSuggestionListener {
                override fun onSuggestionSelect(index: Int) = false

                override fun onSuggestionClick(index: Int): Boolean {
                    val cursor = suggestionsAdapter.getItem(index) as Cursor
                    var selection = cursor.getString(cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_1))
                    if (!isLocalSearch)
                        selection = "\"$selection\"\\$"
                    else if (selection.split(' ').size > 1)
                        selection = "\"$selection\""

                    val query = searchView.query?.let { it.replace(getLastWord(it.toString().trimStart('-')).toRegex(), selection) }
                    searchView.setQuery(query, true)
                    return true
                }
            })
        }
    }

    private fun handleSearchSuggestion(query: String?) {
        query?.let {
            val cursor = MatrixCursor(arrayOf(BaseColumns._ID, SearchManager.SUGGEST_COLUMN_TEXT_1))
            val lastWord = getLastWord(it).trim('"', ' ').trimStart('-')
            if (!lastWord.isBlank()) {
                for ((i, suggestion) in ServerManager.tagSuggestions.withIndex()) {
                    if (suggestion.contains(lastWord))
                        cursor.addRow(arrayOf(i, suggestion.displayTag))
                }
            }

            searchView.suggestionsAdapter?.changeCursor(cursor)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.archive_list_menu, menu)
        if (activity is ArchiveSearch)
            menu.findItem(R.id.refresh_archives)?.isVisible = false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.refresh_archives -> {
                forceArchiveListUpdate()
                true
            }
            else -> false
        }
    }

    fun updateSortMethod(method: SortMethod, desc: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        updateSortMethod(method, desc, prefs)
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
            viewModel?.updateSort(method, descending)
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
        } else {
            throw RuntimeException("$context must implement OnListFragmentInteractionListener")
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val serverSource = viewModel?.archiveList?.value?.dataSource as? ArchiveListServerSource
        serverSource?.run {
            searchResults?.let { outState.putStringArray(RESULTS_KEY, it.toTypedArray()) }
            outState.putInt(RESULTS_SIZE_KEY, totalSize)
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
        DatabaseReader.refreshListener = null

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    fun forceArchiveListUpdate() {
        searchJob?.cancel()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { DatabaseReader.updateArchiveList(requireContext().filesDir, true) }

            if (isLocalSearch)
                getViewModel<ArchiveViewModel>().filter(searchView.query, newCheckBox.isChecked)
            else if (!searchView.query.isNullOrEmpty() || newCheckBox.isChecked) {
                searchJob?.cancel()
                swipeRefreshLayout.isRefreshing = false
                val searchResult = withContext(Dispatchers.IO) {
                    WebHandler.searchServer(searchView.query, newCheckBox.isChecked, sortMethod, descending)
                }
                getViewModel<SearchViewModel>().filter(searchResult)
            }
            else
                viewModel?.reset()
        }
    }

    private fun <T> getViewModel(init: Boolean = true) : T where T : SearchViewModelBase {
        val isStaticCategory = viewModel is StaticCategoryModel
        if (viewModel == null || isStaticCategory)
            initViewModel(isLocalSearch, false, init || isStaticCategory)

        return viewModel as T
    }

    private fun initViewModel(localSearch: Boolean, force: Boolean = false, init: Boolean = true) {
        val model = if (localSearch) {
            ViewModelProviders.of(this).get(ArchiveViewModel::class.java).also {
                if (init)
                    it.init(sortMethod, descending, searchView.query, newCheckBox.isChecked)
            }
        } else {
            ViewModelProviders.of(this).get(SearchViewModel::class.java).also {
                if (init)
                    it.init(sortMethod, descending, force)
            }
        }

        model.archiveList?.observe(viewLifecycleOwner, Observer {
            (listView.adapter as ArchiveRecyclerViewAdapter).submitList(it)
            val size = it.size
            (activity as AppCompatActivity).supportActionBar?.subtitle = resources.getQuantityString(R.plurals.archive_count, size, size)
        })

        model.updateSort(sortMethod, descending)
        viewModel = model
    }

    private fun resetSearch(local: Boolean, force: Boolean = false) {
        newCheckBox.isChecked = false
        searchView.setQuery("", true)
        searchJob?.cancel()
        searchView.clearFocus()
        isLocalSearch = local
        initViewModel(local, force)
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, prefName: String?) {
        when (prefName) {
            getString(R.string.local_search_key) -> {
                val local = prefs?.getBoolean(prefName, false) ?: false
                //Reset filter when changing search type.
                resetSearch(local)
            }
            getString(R.string.search_page_key) -> resetSearch(isLocalSearch, true)
            getString(R.string.search_delay_key) -> { searchDelay = prefs.castStringPrefToLong(prefName, DEFAULT_SEARCH_DELAY) }
            getString(R.string.swipe_refresh_key) -> swipeRefreshLayout.isEnabled = prefs?.getBoolean(prefName, true) ?: true
        }
    }

    override fun isRefreshing(refreshing: Boolean) {
        lifecycleScope.launch { swipeRefreshLayout.isRefreshing = refreshing }
    }

    interface OnListFragmentInteractionListener {
        fun onListFragmentInteraction(archive: Archive?)
    }
}

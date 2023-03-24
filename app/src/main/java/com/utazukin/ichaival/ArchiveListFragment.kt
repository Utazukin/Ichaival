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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProviders
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.utazukin.ichaival.database.DatabaseReader
import com.utazukin.ichaival.database.DatabaseRefreshListener
import com.utazukin.ichaival.database.SearchViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.min

const val STATIC_CATEGORY_SEARCH = "\b"
private const val RESULTS_KEY = "search_results"
private const val RESULTS_SIZE_KEY = "search_size"
private const val DEFAULT_SEARCH_DELAY = 750L
private const val RANDOM_COUNT_KEY = "random_count"

class ArchiveListFragment : Fragment(), DatabaseRefreshListener, SharedPreferences.OnSharedPreferenceChangeListener, AddCategoryListener {
    private var sortMethod = SortMethod.Alpha
    private var descending = false
    private var jumpToTop = false
    private var searchJob: Job? = null
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var listView: RecyclerView
    private lateinit var newCheckBox: CheckBox
    private lateinit var randomButton: Button
    private lateinit var listAdapter: ArchiveRecyclerViewAdapter
    private var menu: Menu? = null
    private lateinit var viewModel: SearchViewModel
    lateinit var searchView: SearchView
        private set
    private var searchDelay: Long = DEFAULT_SEARCH_DELAY
    private var isLocalSearch: Boolean = false
    private var creatingView = false
    private var savedState: Bundle? = null
    private var canSwipeRefresh = false
    private var randomCount: UInt? = null


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_archive_list, container, false)
        listView = view.findViewById(R.id.list)

        with(requireActivity() as MenuHost) {
            addMenuProvider(object: MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(R.menu.archive_list_menu, menu)
                    (this@ArchiveListFragment).menu = menu
                    when (activity) {
                        is ArchiveSearch, is ArchiveRandomActivity -> {
                            with (menu) {
                                findItem(R.id.refresh_archives)?.isVisible = false
                                findItem(R.id.filter_menu)?.isVisible = false
                            }
                        }
                    }
                }

                override fun onMenuItemSelected(item: MenuItem): Boolean {
                    return when (item.itemId) {
                        R.id.refresh_archives -> {
                            forceArchiveListUpdate()
                            true
                        }
                        R.id.select_archives -> (listView.adapter as? ArchiveRecyclerViewAdapter)?.run { enableMultiSelect(requireActivity() as AppCompatActivity) } ?: false
                        R.id.scroll_top -> {
                            listView.layoutManager?.scrollToPosition(0)
                            true
                        }
                        R.id.scroll_bottom -> {
                            listView.layoutManager?.scrollToPosition((listView.layoutManager?.itemCount ?: 1) - 1)
                            true
                        }
                        R.id.go_to_page -> {
                            val archiveCount = listView.adapter?.itemCount ?: 0
                            val dialog = AlertDialog.Builder(requireContext()).apply {
                                val pageCount = ceil(archiveCount.toFloat() / ServerManager.pageSize).toInt()
                                val pages = Array(pageCount) { (it + 1).toString() }
                                val current = when (val layoutManager = listView.layoutManager) {
                                    is LinearLayoutManager -> layoutManager.findFirstCompletelyVisibleItemPosition() / ServerManager.pageSize
                                    else -> -1
                                }

                                setSingleChoiceItems(pages, current) { dialog, id ->
                                    val position = min(id * ServerManager.pageSize, archiveCount)
                                    val layoutManager = listView.layoutManager
                                    if (layoutManager is LinearLayoutManager)
                                        layoutManager.scrollToPositionWithOffset(position, 0)
                                    dialog.dismiss()
                                }
                            }.create()
                            dialog.show()
                            true
                        }
                        else -> false
                    }
                }
            }, viewLifecycleOwner, Lifecycle.State.RESUMED)
        }

        savedState = savedInstanceState
        creatingView = true
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        searchDelay = prefs.castStringPrefToLong(getString(R.string.search_delay_key), DEFAULT_SEARCH_DELAY)
        isLocalSearch = prefs.getBoolean(getString(R.string.local_search_key), false)

        val archiveViewType = ListViewType.fromString(requireContext(), prefs.getString(getString(R.string.archive_list_type_key), ""))

        // Set the adapter
        with(listView) {
            post {
                val dpWidth = getDpWidth(width)
                val itemWidth = getDpWidth(resources.getDimension(if (archiveViewType == ListViewType.Card) R.dimen.archive_card_width else R.dimen.archive_cover_width).toInt())
                val columns = dpWidth.floorDiv(itemWidth)
                layoutManager = if (columns > 1) {
                    object : GridLayoutManager(context, columns) {
                        override fun onLayoutCompleted(state: RecyclerView.State?) {
                            super.onLayoutCompleted(state)
                            if (jumpToTop) {
                                scrollToPosition(0)
                                jumpToTop = false
                            }
                        }
                    }
                } else {
                    object : LinearLayoutManager(context) {
                        override fun onLayoutCompleted(state: RecyclerView.State?) {
                            super.onLayoutCompleted(state)
                            if (jumpToTop) {
                                scrollToPosition(0)
                                jumpToTop = false
                            }
                        }
                    }
                }
            }
            listAdapter = ArchiveRecyclerViewAdapter(this@ArchiveListFragment, ::handleArchiveLongPress)
            listAdapter.registerAdapterDataObserver(object: RecyclerView.AdapterDataObserver() {
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    super.onItemRangeInserted(positionStart, itemCount)
                    val size = listAdapter.itemCount
                    jumpToTop = savedState == null
                    (activity as? AppCompatActivity)?.run { supportActionBar?.subtitle = resources.getQuantityString(R.plurals.archive_count, size, size) }
                }

                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                    super.onItemRangeRemoved(positionStart, itemCount)
                    val size = listAdapter.itemCount
                    jumpToTop = savedState == null
                    (activity as? AppCompatActivity)?.run { supportActionBar?.subtitle = resources.getQuantityString(R.plurals.archive_count, size, size) }
                }
            })
        }

        searchView = view.findViewById(R.id.archive_search)
        newCheckBox = view.findViewById(R.id.new_checkbox)
        newCheckBox.setOnCheckedChangeListener { _, checked ->
            listAdapter.disableMultiSelect()
            viewModel.onlyNew = checked
        }

        setupTagSuggestions()
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                listAdapter.disableMultiSelect()
                viewModel.filter(query)
                enableRefresh(query.isNullOrEmpty())
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(query: String?): Boolean {
                handleSearchSuggestion(query)
                if (creatingView)
                    return true

                val categoryFragment: CategoryFilterFragment? = requireActivity().supportFragmentManager.findFragmentById(R.id.category_fragment) as? CategoryFilterFragment
                categoryFragment?.selectedCategory?.let {
                    if (it is StaticCategory && query == STATIC_CATEGORY_SEARCH)
                        return true
                    if (it is StaticCategory || (it is DynamicCategory && it.search != query))
                        categoryFragment.clearCategory()
                }

                if (query?.startsWith(STATIC_CATEGORY_SEARCH) == true) {
                    listAdapter.disableMultiSelect()
                    searchView.setQuery(query.removePrefix(STATIC_CATEGORY_SEARCH), false)
                    return false
                }

                enableRefresh(query.isNullOrEmpty())
                if (searchDelay <= 0 && !query.isNullOrBlank())
                    return true

                if (isLocalSearch)
                    viewModel.filter(query)
                else {
                    searchJob?.cancel()
                    swipeRefreshLayout.isRefreshing = false
                    searchJob = lifecycleScope.launch {
                        if (!query.isNullOrBlank() || newCheckBox.isChecked)
                            delay(searchDelay)

                        if (query != null) {
                            listAdapter.disableMultiSelect()
                            viewModel.filter(query)
                        }
                    }
                }
                return true
            }
        })
        searchView.clearFocus()

        randomButton = view.findViewById(R.id.random_button)
        randomButton.setOnClickListener {
            listAdapter.disableMultiSelect()
            val randomCount = prefs.castStringPrefToInt(getString(R.string.random_count_pref), 1)
            if (randomCount > 1 && ServerManager.checkVersionAtLeast(0, 8, 2)) {
                val intent = Intent(context, ArchiveRandomActivity::class.java)
                val bundle = Bundle().apply {
                    if (searchView.query.isNotBlank() && searchView.query?.startsWith(STATIC_CATEGORY_SEARCH) != true)
                        putString(RANDOM_SEARCH, searchView.query.toString())
                    val categoryFragment = requireActivity().supportFragmentManager.findFragmentById(R.id.category_fragment) as? CategoryFilterFragment
                    categoryFragment?.selectedCategory?.run { putString(RANDOM_CAT, id) }
                }
                intent.putExtras(bundle)
                startActivity(intent)
            } else {
                lifecycleScope.launch {
                    val archive = viewModel.getRandom(false)
                    if (archive != null)
                        startDetailsActivity(archive.id, requireContext())
                }
            }
        }

        randomButton.setOnLongClickListener {
            listAdapter.disableMultiSelect()
            lifecycleScope.launch {
                val archive = viewModel.getRandom()
                if (archive != null && !ReaderTabHolder.isTabbed(archive.id))
                    ReaderTabHolder.addTab(archive, 0)
            }
            true
        }

        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh)

        canSwipeRefresh = prefs.getBoolean(getString(R.string.swipe_refresh_key), true)
        swipeRefreshLayout.setOnRefreshListener { forceArchiveListUpdate() }
        swipeRefreshLayout.isEnabled = canSwipeRefresh

        return view
    }

    override fun onStart() {
        super.onStart()
        if (activity is ArchiveSearch || activity is ArchiveRandomActivity) {
            with(requireActivity().intent) {
                val tag = getStringExtra(TAG_SEARCH)

                showOnlySearch(true)
                if (activity is ArchiveSearch)
                    searchView.setQuery(tag, false)
                else
                    searchView.visibility = View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WebHandler.registerRefreshListener(this)
    }

    override fun onPause() {
        super.onPause()
        WebHandler.unregisterRefreshListener(this)
    }

    fun refreshRandom() = viewModel.reset()

    fun setupRandomList(count: Int = -1) {
        lifecycleScope.launch {
            with(requireActivity().intent) {
                val filter = getStringExtra(RANDOM_SEARCH)
                val category = getStringExtra(RANDOM_CAT)
                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val count = when {
                    count > 0 -> count.toUInt()
                    randomCount != null -> randomCount!!
                    else -> prefs.castStringPrefToInt(getString(R.string.random_count_pref), 5).toUInt()
                }
                randomCount = count

                viewModel.deferReset {
                    isLocal = isLocalSearch
                    init(sortMethod, descending, filter, newCheckBox.isChecked)
                    updateResults(emptyList(), category)
                    monitor(lifecycleScope) { listAdapter.submitData(it) }
                    randomCount = count
                }
                if (savedState == null) {
                    jumpToTop = true
                    if (listView.adapter == null)
                        listView.adapter = listAdapter
                } else {
                    savedState?.run {
                        getStringArray(RESULTS_KEY)?.let {
                            viewModel.updateResults(it.asList(), category)
                        }
                    }
                    listView.adapter = listAdapter
                    savedState = null
                }
                creatingView = false
            }
        }
    }

    fun setupArchiveList() {
        lifecycleScope.launch {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            DatabaseReader.updateArchiveList(requireContext())

            val method = SortMethod.fromInt(prefs.getInt(getString(R.string.sort_pref), 1))
            val desc = prefs.getBoolean(getString(R.string.desc_pref), false)

            with(viewModel) {
                isLocal = isLocalSearch
                init(method, desc, searchView.query, newCheckBox.isChecked, isSearch = activity is ArchiveSearch)
                monitor(lifecycleScope) { listAdapter.submitData(it) }
                randomCount = 0u
            }
            when {
                isLocalSearch -> viewModel.filter(searchView.query)
                savedState?.getInt(RESULTS_SIZE_KEY, -1) ?: -1 > 0 -> {
                    savedState?.run {
                        getStringArray(RESULTS_KEY)?.let {
                            viewModel.updateResults(it.asList())
                        }
                    }
                }
                searchView.query == STATIC_CATEGORY_SEARCH -> {
                    val categoryFragment = requireActivity().supportFragmentManager.findFragmentById(R.id.category_fragment) as? CategoryFilterFragment
                    (categoryFragment?.selectedCategory as? StaticCategory)?.let { handleCategoryChange(it) }
                }
                !searchView.query.isNullOrEmpty() -> searchView.query?.let { viewModel.filter(it) }
            }

            listView.adapter = listAdapter
            if (savedState == null)
                updateSortMethod(method, desc, prefs)
            else {
                sortMethod = method
                descending = desc
            }

            savedState = null
            creatingView = false
        }
    }

    fun handleCategoryChange(category: ArchiveCategory) {
        if (category is DynamicCategory) {
            searchView.setQuery(category.search, true)
            searchView.clearFocus()
            enableRefresh(false)
        }
        else if (category is StaticCategory) {
            searchView.setQuery(STATIC_CATEGORY_SEARCH, false)
            searchView.clearFocus()
            enableRefresh(false)

            viewModel.updateResults(category.archiveIds, category.id)
        }
    }

    private fun setupTagSuggestions() {
        val from = arrayOf(SearchManager.SUGGEST_COLUMN_TEXT_1)
        val to = intArrayOf(R.id.search_suggestion)
        val adapter = SimpleCursorAdapter(context, R.layout.search_suggestion_layout, null, from, to, CursorAdapter.FLAG_REGISTER_CONTENT_OBSERVER)

        with(searchView) {
            suggestionsAdapter = adapter

            setOnSuggestionListener(object: SearchView.OnSuggestionListener {
                override fun onSuggestionSelect(index: Int) = false

                override fun onSuggestionClick(index: Int): Boolean {
                    val cursor = suggestionsAdapter.getItem(index) as Cursor
                    var selection = cursor.getString(cursor.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_TEXT_1))
                    if (!isLocalSearch)
                        selection = "\"$selection\"\$"
                    else if (selection.split(' ').size > 1)
                        selection = "\"$selection\""

                    val query = searchView.query?.let {
                        val terms = parseTermsInfo(it)
                        val builder = StringBuilder()
                        for (info in terms.dropLast(1)) {
                            builder.append(
                                when {
                                    info.term.endsWith('$') && info.exact -> "\"${info.term.removeSuffix("$")}\"$"
                                    info.exact -> "\"${info.term}\""
                                    else -> info.term
                                }
                            )
                            builder.append(" ")
                        }
                        builder.append(selection)
                        builder.toString()
                    }
                    searchView.setQuery(query, true)
                    return true
                }
            })
        }
    }

    private fun handleSearchSuggestion(query: String?) {
        query?.let {
            val cursor = MatrixCursor(arrayOf(BaseColumns._ID, SearchManager.SUGGEST_COLUMN_TEXT_1))
            val lastWord = parseTerms(it).lastOrNull()?.trimStart('-')
            if (!lastWord.isNullOrBlank()) {
                for ((i, suggestion) in ServerManager.tagSuggestions.withIndex()) {
                    if (suggestion.contains(lastWord))
                        cursor.addRow(arrayOf(i, suggestion.displayTag))
                }
            }

            searchView.suggestionsAdapter?.changeCursor(cursor)
        }
    }

    private fun enableRefresh(enable: Boolean) {
        menu?.findItem(R.id.refresh_archives)?.isVisible = enable
        swipeRefreshLayout.isEnabled = canSwipeRefresh && enable
    }

    fun updateSortMethod(method: SortMethod, desc: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireActivity())
        updateSortMethod(method, desc, prefs)
    }

    private fun handleArchiveLongPress(archive: Archive) : Boolean {
        parentFragmentManager.let {
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
            jumpToTop = true
        }
    }

    private fun showOnlySearch(show: Boolean){
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

    private fun startDetailsActivity(id: String, context: Context) {
        val intent = Intent(context, ArchiveDetails::class.java)
        val bundle = Bundle()
        bundle.putString("id", id)
        intent.putExtras(bundle)
        startActivity(intent)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.registerOnSharedPreferenceChangeListener(this)
        viewModel = ViewModelProviders.of(this)[SearchViewModel::class.java]
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        with(viewModel) {
            searchResults?.let { outState.putStringArray(RESULTS_KEY, it.toTypedArray()) }
            val total = totalSize
            if (total > 0)
                outState.putInt(RESULTS_SIZE_KEY, total)
        }

        if (activity is ArchiveRandomActivity)
            randomCount?.let { outState.putInt(RANDOM_COUNT_KEY, it.toInt()) }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        savedInstanceState?.run {
            val count = getInt(RANDOM_COUNT_KEY, -1)
            if (count > 0)
                randomCount = count.toUInt()
        }
    }

    override fun onDetach() {
        super.onDetach()

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    private fun forceArchiveListUpdate() {
        with(listView.adapter as ArchiveRecyclerViewAdapter) { disableMultiSelect() }
        searchJob?.cancel()
        lifecycleScope.launch {
            DatabaseReader.updateArchiveList(requireContext(), true)
            viewModel.reset()
        }
    }

    private fun resetSearch(local: Boolean) {
        newCheckBox.isChecked = false
        searchView.setQuery("", true)
        searchJob?.cancel()
        searchView.clearFocus()
        isLocalSearch = local
        viewModel.deferReset {
            isLocal = local
            onlyNew = false
            filter("")
        }
    }

    override fun onAddedToCategory(category: ArchiveCategory, archiveIds: List<String>) {
        with(listView.adapter as ArchiveRecyclerViewAdapter) { onAddedToCategory(category) }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, prefName: String?) {
        when (prefName) {
            getString(R.string.local_search_key) -> {
                val local = prefs?.getBoolean(prefName, false) ?: false
                //Reset filter when changing search type.
                if (local != isLocalSearch)
                    resetSearch(local)
            }
            getString(R.string.search_delay_key) -> { searchDelay = prefs.castStringPrefToLong(prefName, DEFAULT_SEARCH_DELAY) }
            getString(R.string.swipe_refresh_key) -> {
                canSwipeRefresh = prefs?.getBoolean(prefName, true) ?: true
                swipeRefreshLayout.isEnabled = canSwipeRefresh
            }
        }
    }

    override fun isRefreshing(refreshing: Boolean) {
        lifecycleScope.launch {
            if (refreshing)
                swipeRefreshLayout.isEnabled = true
            swipeRefreshLayout.isRefreshing = refreshing
            if (!refreshing)
                swipeRefreshLayout.isEnabled = canSwipeRefresh
        }
    }

    interface OnListFragmentInteractionListener {
        fun onListFragmentInteraction(archive: Archive?, view: View)
    }
}

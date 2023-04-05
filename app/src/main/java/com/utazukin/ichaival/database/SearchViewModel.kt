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

package com.utazukin.ichaival.database

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import com.utazukin.ichaival.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.min

class ReaderTabViewModel : ViewModel() {
    private val bookmarks = Pager(PagingConfig(5)) { DatabaseReader.database.archiveDao().getDataBookmarks() }.flow.cachedIn(viewModelScope)
    fun monitor(scope: CoroutineScope, action: suspend (PagingData<ReaderTab>) -> Unit) {
        scope.launch { bookmarks.collectLatest(action) }
    }
}

class SearchViewModel : ViewModel(), DatabaseDeleteListener, CategoryListener {
    val totalSize get() = (archivePagingSource as? ArchiveListServerPagingSource)?.totalSize ?: searchResults?.size ?: 0
    var searchResults: List<String>? = null
        private set
    var onlyNew = false
        set(value) {
            if (field != value) {
                field = value
                reset()
            }
        }
    var isLocal = false
        set(value){
            if (field != value) {
                field = value
                reset()
            }
        }
    var randomCount = 0u
        set(value) {
            if (field != value) {
                field = value
                reset()
            }
        }

    private var resetDisabled = true
        set(value) {
            if (field != value) {
                field = value
                if (!field)
                    reset()
            }
        }

    private var sortMethod = SortMethod.Alpha
    private var descending = false
    private var isSearch = false
    private var filter: CharSequence? = null
    private var archivePagingSource: PagingSource<Int, Archive>? = null
    private val archiveDao by lazy { database.archiveDao() }
    private val database get() = DatabaseReader.database
    private var filterJob: Job? = null
    private lateinit var archiveList: Flow<PagingData<Archive>>
    private var categoryId: String? = null
    private var initiated = false

    init {
        DatabaseReader.registerDeleteListener(this)
        CategoryManager.addUpdateListener(this)
    }

    private fun getPagingSource() : PagingSource<Int, Archive> {
        val source = when {
            randomCount > 0u -> ArchiveListRandomPagingSource(filter ?: "", randomCount, categoryId, database)
            categoryId != null -> database.getStaticCategorySource(categoryId, sortMethod, descending, onlyNew) ?: EmptySource()
            !isLocal && filter?.isNotEmpty() == true -> ArchiveListServerPagingSource(isSearch, onlyNew, sortMethod, descending, filter ?: "", database)
            isSearch && searchResults?.isEmpty() != false -> EmptySource()
            filter.isNullOrEmpty() || isLocal || searchResults != null -> database.getArchiveSource(searchResults, sortMethod, descending, onlyNew)
            else -> ArchiveListServerPagingSource(isSearch, onlyNew, sortMethod, descending, filter ?: "", database)
        }
        archivePagingSource = source
        return source
    }

    suspend fun getRandom(excludeBookmarked: Boolean = true): Archive? {
        return if (excludeBookmarked)
            archiveDao.getRandomExcludeBookmarked()
        else
            archiveDao.getRandom()
    }

    fun deferReset(block: SearchViewModel.() -> Unit) {
        resetDisabled = true
        block()
        resetDisabled = false
    }

    fun updateSort(method: SortMethod, desc: Boolean, force: Boolean = false) {
        if (force || method != sortMethod || desc != descending) {
            sortMethod = method
            descending = desc
            reset()
        }
    }

    fun updateResults(categoryId: String?){
        this.categoryId = categoryId
        searchResults = null
        reset()
    }

    fun updateResults(results: List<String>) {
        searchResults = results
        categoryId = null
        reset()
    }

    fun filter(search: CharSequence?) {
        filter = search
        categoryId = null
        searchResults = null
        if (isLocal) {
            filterJob?.cancel()
            if (search == null) {
                searchResults = emptyList()
                reset()
            } else if (search.isEmpty()) {
                searchResults = null
                reset()
            } else {
                filterJob = viewModelScope.launch(Dispatchers.IO) {
                    searchResults = internalFilter(search)
                    yield()
                    reset()
                }
            }
        } else reset()
    }

    private suspend fun internalFilter(filter: CharSequence) : List<String> {
        val totalCount = archiveDao.getArchiveCount()
        val mValues = ArrayList<String>(min(totalCount, DatabaseReader.MAX_WORKING_ARCHIVES))
        val terms = parseTermsInfo(filter)
        val titleSearch = filter.removeSurrounding("\"")
        WebHandler.updateRefreshing(true)
        for (i in 0 until totalCount step DatabaseReader.MAX_WORKING_ARCHIVES) {
            val allArchives = archiveDao.getArchives(i, DatabaseReader.MAX_WORKING_ARCHIVES)
            for (archive in allArchives) {
                if (archive.title.contains(titleSearch, ignoreCase = true))
                    mValues.add(archive.id)
                else {
                    for (termInfo in terms) {
                        if (archive.containsTag(termInfo.term, termInfo.exact) != termInfo.negative) {
                            mValues.add(archive.id)
                            break
                        }
                    }
                }
            }
        }
        WebHandler.updateRefreshing(false)

        return mValues
    }

    fun init(method: SortMethod, desc: Boolean, filter: CharSequence?, onlyNew: Boolean, force: Boolean = false, isSearch: Boolean = false) {
        if (!initiated || force) {
            if (!initiated)
                archiveList = Pager(PagingConfig(ServerManager.pageSize), 0) { getPagingSource() }.flow.cachedIn(viewModelScope)
            sortMethod = method
            descending = desc
            this.isSearch = isSearch
            this.onlyNew = onlyNew
            initiated = true
            filter(filter)
            resetDisabled = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        DatabaseReader.unregisterDeleteListener(this)
        CategoryManager.removeUpdateListener(this)
    }

    fun reset() {
        if (resetDisabled)
            return

        filterJob?.cancel()
        filterJob = null
        archivePagingSource?.invalidate()
    }

    fun monitor(scope: CoroutineScope, action: suspend (PagingData<Archive>) -> Unit) {
        scope.launch { archiveList.collectLatest(action) }
    }

    override fun onDelete() {
        categoryId = null
        reset()
    }

    override fun onCategoriesUpdated(categories: List<ArchiveCategory>?) {
        if (categoryId != null) {
            categoryId = null
            searchResults = null
            reset()
        }
    }
}
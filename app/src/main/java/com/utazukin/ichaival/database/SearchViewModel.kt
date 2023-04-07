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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ReaderTabViewModel : ViewModel() {
    private val bookmarks = Pager(PagingConfig(5)) { DatabaseReader.database.archiveDao().getDataBookmarks() }.flow.cachedIn(viewModelScope)
    fun monitor(scope: CoroutineScope, action: suspend (PagingData<ReaderTab>) -> Unit) {
        scope.launch { bookmarks.collectLatest(action) }
    }
}

class SearchViewModel : ViewModel(), DatabaseDeleteListener, CategoryListener {
    val totalSize get() = (archivePagingSource as? ArchiveListPagingSourceBase)?.totalSize ?: 0
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
    private var filter = ""
    private var archivePagingSource: PagingSource<Int, Archive>? = null
    private val archiveDao by lazy { database.archiveDao() }
    private val database get() = DatabaseReader.database
    private lateinit var archiveList: Flow<PagingData<Archive>>
    private var categoryId = ""
    private var initiated = false

    init {
        DatabaseReader.registerDeleteListener(this)
        CategoryManager.addUpdateListener(this)
    }

    private fun getPagingSource() : PagingSource<Int, Archive> {
        val source = when {
            randomCount > 0u -> ArchiveListRandomPagingSource(filter, randomCount, categoryId, database)
            categoryId.isNotEmpty() -> database.getStaticCategorySource(categoryId, sortMethod, descending, onlyNew)
            isLocal && filter.isNotEmpty() -> ArchiveListLocalPagingSource(filter, sortMethod, descending, onlyNew, database)
            filter.isNotEmpty() -> ArchiveListServerPagingSource(onlyNew, sortMethod, descending, filter, database)
            isSearch -> EmptySource()
            else -> database.getArchiveSource(searchResults, sortMethod, descending, onlyNew)
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
        this.categoryId = categoryId ?: ""
        searchResults = null
        reset()
    }

    fun updateResults(results: List<String>) {
        searchResults = results
        categoryId = ""
        reset()
    }

    fun filter(search: CharSequence?) {
        filter = search?.toString() ?: ""
        categoryId = ""
        searchResults = null
        reset()
    }

    fun init(method: SortMethod, desc: Boolean, filter: CharSequence?, onlyNew: Boolean, force: Boolean = false, isSearch: Boolean = false) {
        if (!initiated || force) {
            if (!initiated)
                archiveList = Pager(PagingConfig(ServerManager.pageSize, jumpThreshold = ServerManager.pageSize * 3), 0) { getPagingSource() }.flow.cachedIn(viewModelScope)
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

        archivePagingSource?.invalidate()
    }

    fun monitor(scope: CoroutineScope, action: suspend (PagingData<Archive>) -> Unit) {
        scope.launch { archiveList.collectLatest(action) }
    }

    override fun onDelete() {
        categoryId = ""
        reset()
    }

    override fun onCategoriesUpdated(categories: List<ArchiveCategory>?) {
        if (categoryId.isNotEmpty()) {
            if (categories?.any { it.id  == categoryId } != true)
                categoryId = ""
            searchResults = null
            reset()
        }
    }
}
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ReaderTabViewModel : ViewModel() {
    private val bookmarks = Pager(PagingConfig(5)) { DatabaseReader.database.archiveDao().getDataBookmarks() }.flow.cachedIn(viewModelScope)
    fun monitor(scope: CoroutineScope, action: suspend (PagingData<ReaderTab>) -> Unit) {
        scope.launch { bookmarks.collectLatest(action) }
    }
}

class SearchViewModel : ViewModel(), CategoryListener {
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
    private var archivePagingSource: PagingSource<Int, Archive> = EmptySource()
    private val database = DatabaseReader.database
    private val archiveList = Pager(PagingConfig(ServerManager.pageSize, jumpThreshold = ServerManager.pageSize * 3), 0) { getPagingSource() }.flow.cachedIn(viewModelScope)
    private var categoryId = ""
    private var initiated = false

    init {
        CategoryManager.addUpdateListener(this)
    }

    private fun getPagingSource() : PagingSource<Int, Archive> {
        archivePagingSource = when {
            !initiated -> EmptySource()
            randomCount > 0u -> ArchiveListRandomPagingSource(filter, randomCount, categoryId, database)
            categoryId.isNotEmpty() -> database.getStaticCategorySource(categoryId, sortMethod, descending, onlyNew)
            isLocal && filter.isNotEmpty() -> ArchiveListLocalPagingSource(filter, sortMethod, descending, onlyNew, database)
            filter.isNotEmpty() -> ArchiveListServerPagingSource(onlyNew, sortMethod, descending, filter, database)
            isSearch -> EmptySource()
            else -> database.getArchiveSource(sortMethod, descending, onlyNew)
        }
        return archivePagingSource
    }

    suspend fun getRandom(excludeBookmarked: Boolean = true): Archive? {
        return if (excludeBookmarked)
            database.archiveDao().getRandomExcludeBookmarked()
        else
            database.archiveDao().getRandom()
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
        reset()
    }

    fun filter(search: CharSequence?) {
        filter = search?.toString() ?: ""
        categoryId = ""
        reset()
    }

    fun init(method: SortMethod, desc: Boolean, filter: CharSequence?, onlyNew: Boolean, force: Boolean = false, isSearch: Boolean = false) {
        if (!initiated || force) {
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
        CategoryManager.removeUpdateListener(this)
    }

    fun reset() {
        if (resetDisabled)
            return

        archivePagingSource.let {
            when (it) {
                is ArchiveListPagingSourceBase -> it.reset()
                else -> it.invalidate()
            }
        }
    }

    fun monitor(scope: CoroutineScope, action: suspend (PagingData<Archive>) -> Unit) {
        scope.launch { archiveList.collectLatest(action) }
    }

    override fun onCategoriesUpdated(categories: List<ArchiveCategory>?) {
        if (categoryId.isNotEmpty() && categories?.any { it.id  == categoryId } != true) {
            categoryId = ""
            reset()
        }
    }
}
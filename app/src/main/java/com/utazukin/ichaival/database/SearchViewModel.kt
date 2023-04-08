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

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import com.utazukin.ichaival.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.reflect.KProperty

class ReaderTabViewModel : ViewModel() {
    private val bookmarks = Pager(PagingConfig(5)) { DatabaseReader.getDataBookmarks() }.flow.cachedIn(viewModelScope)
    fun monitor(scope: CoroutineScope, action: suspend (PagingData<ReaderTab>) -> Unit) {
        scope.launch { bookmarks.collectLatest(action) }
    }
}

private class StateDelegate<T>(private val key: String,
                               private val state: SavedStateHandle,
                               private val default: T,
                               private val onChange: (() -> Unit)? = null) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>) = state.get<T>(key) ?: default

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        val old = state.get<T>(key) ?: default
        state[key] = value
        if (old != value)
            onChange?.invoke()
    }
}

private class ChangeDelegate<T>(private var field: T, private val onChange: () -> Unit) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>) = field
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        if (field != value) {
            field = value
            onChange()
        }
    }
}

class SearchViewModel(state: SavedStateHandle) : ViewModel(), CategoryListener {
    var onlyNew by StateDelegate("new", state, false) { reset(false) }
    var isLocal by StateDelegate("local", state, false) { reset(false) }
    var randomCount by StateDelegate("randCount", state, 0) { reset() }
    var sortMethod by StateDelegate("sort", state, SortMethod.Alpha)
        private set
    var descending by StateDelegate("desc", state, false)
        private set
    private var initiated by StateDelegate("init", state, false)
    private var resetDisabled by ChangeDelegate(!initiated) { reset(false) }
    private var isSearch by StateDelegate("search", state, false)
    private var filter by StateDelegate("filter", state, "")
    private var categoryId by StateDelegate("category", state, "")
    private var archivePagingSource: PagingSource<Int, Archive> = EmptySource()
    private val archiveList = Pager(PagingConfig(ServerManager.pageSize, jumpThreshold = ServerManager.pageSize * 3), 0) { getPagingSource() }.flow.cachedIn(viewModelScope)

    init {
        CategoryManager.addUpdateListener(this)
    }

    private fun getPagingSource() : PagingSource<Int, Archive> {
        archivePagingSource = when {
            !initiated -> EmptySource()
            randomCount > 0 -> ArchiveListRandomPagingSource(filter, randomCount, categoryId)
            categoryId.isNotEmpty() -> DatabaseReader.getStaticCategorySource(categoryId, sortMethod, descending, onlyNew)
            isLocal && filter.isNotEmpty() -> ArchiveListLocalPagingSource(filter, sortMethod, descending, onlyNew)
            filter.isNotEmpty() -> ArchiveListServerPagingSource(onlyNew, sortMethod, descending, filter)
            isSearch -> EmptySource()
            else -> DatabaseReader.getArchiveSource(sortMethod, descending, onlyNew)
        }
        return archivePagingSource
    }

    fun deferReset(block: SearchViewModel.() -> Unit) {
        resetDisabled = true
        block()
        resetDisabled = false
    }

    fun updateSort(method: SortMethod, desc: Boolean) {
        if (method != sortMethod || desc != descending) {
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
        if (filter != search || categoryId.isNotEmpty()) {
            filter = search?.toString() ?: ""
            categoryId = ""
            reset()
        }
    }

    fun init(filter: CharSequence?, onlyNew: Boolean, isSearch: Boolean = false) = init(SortMethod.Alpha, false, filter, onlyNew, isSearch)

    fun init(method: SortMethod, desc: Boolean, filter: CharSequence?, onlyNew: Boolean, isSearch: Boolean = false) {
        if (!initiated) {
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

    fun reset() = reset(true)

    private fun reset(force: Boolean) {
        if (resetDisabled || (randomCount > 0 && !force))
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

    override fun onCategoriesUpdated(categories: List<ArchiveCategory>?, firstUpdate: Boolean) {
        if (!firstUpdate && categoryId.isNotEmpty() && categories?.any { it.id  == categoryId } != true) {
            categoryId = ""
            reset()
        }
    }
}
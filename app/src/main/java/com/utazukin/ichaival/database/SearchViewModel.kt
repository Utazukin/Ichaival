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

package com.utazukin.ichaival.database

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import androidx.preference.PreferenceManager
import com.utazukin.ichaival.ArchiveBase
import com.utazukin.ichaival.ArchiveCategory
import com.utazukin.ichaival.CategoryListener
import com.utazukin.ichaival.CategoryManager
import com.utazukin.ichaival.R
import com.utazukin.ichaival.ReaderTab
import com.utazukin.ichaival.ServerManager
import com.utazukin.ichaival.SortMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.reflect.KProperty

class ReaderTabViewModel : ViewModel() {
    private val bookmarks = Pager(PagingConfig(5)) { DatabaseReader.getDataBookmarks() }.flow.cachedIn(viewModelScope)
    fun monitor(action: suspend (PagingData<ReaderTab>) -> Unit) {
        viewModelScope.launch { bookmarks.collectLatest(action) }
    }
}

private class StateDelegate<T>(private val state: SavedStateHandle,
                               private val default: T,
                               private val onChange: (() -> Unit)? = null) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>) = state[property.name] ?: default

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        val old = state[property.name] ?: default
        state[property.name] = value
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

class SearchViewModel(app: Application, state: SavedStateHandle, prefs: SharedPreferences) : AndroidViewModel(app), CategoryListener {
    constructor(app: Application, state: SavedStateHandle) : this(app, state, PreferenceManager.getDefaultSharedPreferences(app.applicationContext))
    var onlyNew by StateDelegate(state, false) { reset(false) }
    var isLocal by StateDelegate(state, prefs.getBoolean(app.resources.getString(R.string.local_search_key), false)) { reset(false) }
    var randomCount by StateDelegate(state, 0) { reset() }
    var categoryId by StateDelegate(state, "") { reset() }
    var isSearch by StateDelegate(state, false) { reset() }
    var sortMethod by StateDelegate(state, SortMethod.fromInt(prefs.getInt(app.resources.getString(R.string.sort_pref), 1))) { jumpToTop = true; reset() }
    var descending by StateDelegate(state, prefs.getBoolean(app.resources.getString(R.string.desc_pref), false)) { jumpToTop = true; reset() }
    var filter by StateDelegate(state, "")
        private set
    var jumpToTop = false
    private var initiated by StateDelegate(state, false) { reset() }
    private var resetDisabled by ChangeDelegate(!initiated) { reset(false) }
    private var archivePagingSource: PagingSource<Int, ArchiveBase> = EmptySource()
    private val archiveList = Pager(PagingConfig(ServerManager.pageSize, jumpThreshold = ServerManager.pageSize * 3), 0) { getPagingSource() }.flow.cachedIn(viewModelScope)

    init {
        CategoryManager.addUpdateListener(this)
    }

    private fun getPagingSource() : PagingSource<Int, ArchiveBase> {
        archivePagingSource = when {
            !initiated -> EmptySource()
            randomCount > 0 -> ArchiveListRandomPagingSource(filter, randomCount, categoryId)
            categoryId.isNotEmpty() && filter.isBlank() -> DatabaseReader.getStaticCategorySource(categoryId, sortMethod, descending, onlyNew)
            isLocal && filter.isNotBlank() -> ArchiveListLocalPagingSource(filter, sortMethod, descending, onlyNew, categoryId)
            filter.isNotBlank() -> ArchiveListServerPagingSource(onlyNew, sortMethod, descending, filter, categoryId)
            isSearch -> EmptySource()
            else -> DatabaseReader.getArchiveSource(sortMethod, descending, onlyNew)
        }
        return archivePagingSource
    }

    fun init() {
        if (!initiated) {
            initiated = true
            resetDisabled = false
        }
    }

    fun deferReset(block: SearchViewModel.() -> Unit) {
        resetDisabled = true
        block()
        resetDisabled = false
    }

    fun filter(search: CharSequence?) {
        if (filter != search) {
            filter = search?.toString() ?: ""
            reset(false)
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

    fun monitor(scope: CoroutineScope, action: suspend (PagingData<ArchiveBase>) -> Unit) {
        scope.launch { archiveList.collectLatest(action) }
    }

    override fun onCategoriesUpdated(categories: List<ArchiveCategory>?, firstUpdate: Boolean) {
        if (!firstUpdate && categoryId.isNotEmpty() && categories?.any { it.id  == categoryId } != true)
            categoryId = ""
    }
}
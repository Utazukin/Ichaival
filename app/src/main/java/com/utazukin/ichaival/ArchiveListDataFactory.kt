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

import androidx.lifecycle.MutableLiveData
import androidx.paging.DataSource
import androidx.paging.PositionalDataSource
import kotlin.math.min

class ArchiveListDataFactory : DataSource.Factory<Int, Archive>() {
    var isSearch = false
    var results: List<String>? = null
        private set
    private val archiveLiveData = MutableLiveData<ArchiveListDataSource>()
    private var sortMethod = SortMethod.Alpha
    private var descending = false

    override fun create(): DataSource<Int, Archive> {
        val latestSource = ArchiveListDataSource(results, sortMethod, descending, isSearch)
        archiveLiveData.postValue(latestSource)
        return latestSource
    }

    fun updateSort(method: SortMethod, desc: Boolean, force: Boolean) {
        if (sortMethod != method || descending != desc || force) {
            sortMethod = method
            descending = desc
            archiveLiveData.value?.invalidate()
        }
    }

    fun updateSearchResults(searchResults: List<String>?) {
        results = searchResults
        archiveLiveData.value?.invalidate()
    }
}

class ArchiveListDataSource(private val results: List<String>?,
                            private val sortMethod: SortMethod,
                            private val descending: Boolean,
                            private val isSearch: Boolean) : PositionalDataSource<Archive>() {

    private val database by lazy { DatabaseReader.database }

    override fun loadRange(params: LoadRangeParams, callback: LoadRangeCallback<Archive>) {
        val ids = results?.let {
            val endIndex = min(params.startPosition + params.loadSize, it.size)
            it.subList(params.startPosition, endIndex)
        }
        val archives = getArchives(ids)
        callback.onResult(archives)
    }

    override fun loadInitial(params: LoadInitialParams, callback: LoadInitialCallback<Archive>) {
        val ids = results?.let {
            val endIndex = min(params.requestedStartPosition + params.requestedLoadSize, it.size)
            it.subList(params.requestedStartPosition, endIndex)
        }

        val archives = getArchives(ids)
        callback.onResult(archives, params.requestedStartPosition, results?.size ?: archives.size)
    }

    private fun getArchives(ids: List<String>?) : List<Archive> {
        if (isSearch && ids == null)
            return emptyList()

        return when (sortMethod) {
            SortMethod.Alpha -> if (descending) database.getTitleDescending(ids) else database.getTitleAscending(ids)
            SortMethod.Date -> if (descending) database.getDateDescending(ids) else database.getDateAscending(ids)
        }
    }
}
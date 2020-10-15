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

import androidx.lifecycle.MutableLiveData
import androidx.paging.DataSource
import androidx.paging.PositionalDataSource
import kotlin.math.min

class ServerSearchResult(val results: List<String>?,
                         val totalSize: Int = 0,
                         val filter: CharSequence = "",
                         val onlyNew: Boolean = false)

class ArchiveListDataFactory(private val localSearch: Boolean) : DataSource.Factory<Int, Archive>() {
    var isSearch = false
    var currentSource: ArchiveDataSourceBase? = null
        private set
    private var results: List<String>? = null
    private val archiveLiveData = MutableLiveData<ArchiveDataSourceBase>()
    private var sortMethod = SortMethod.Alpha
    private var descending = false
    private var totalResultCount = 0
    private var onlyNew = false
    private var filter: CharSequence = ""

    override fun create(): DataSource<Int, Archive> {
        val latestSource = createDataSource()
        currentSource = latestSource
        archiveLiveData.postValue(latestSource)
        return latestSource
    }

    private fun createDataSource() : ArchiveDataSourceBase {
        return if (localSearch)
            ArchiveListDataSource(results, sortMethod, descending, isSearch)
        else
            ArchiveListServerSource(results, sortMethod, descending, isSearch, totalResultCount, onlyNew, filter)
    }

    fun updateSort(method: SortMethod, desc: Boolean, force: Boolean) {
        if (sortMethod != method || descending != desc || force) {
            sortMethod = method
            descending = desc

            if (!localSearch)
                results = null

            archiveLiveData.value?.invalidate()
        }
    }

    fun updateSearchResults(searchResults: List<String>?) {
        results = searchResults
        archiveLiveData.value?.invalidate()
    }

    fun updateSearchResults(searchResult: ServerSearchResult) {
        results = searchResult.results
        onlyNew = searchResult.onlyNew
        filter = searchResult.filter
        totalResultCount = searchResult.totalSize
        archiveLiveData.value?.invalidate()
    }

    fun reset() = archiveLiveData.value?.invalidate()
}

abstract class ArchiveDataSourceBase(protected val sortMethod: SortMethod,
                                     protected val descending: Boolean,
                                     protected val isSearch: Boolean) : PositionalDataSource<Archive>() {
    protected val database by lazy { DatabaseReader.database }
    abstract val searchResults: List<String>?

    protected fun getArchives(ids: List<String>?, offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive> {
        if (isSearch && ids == null)
            return emptyList()

        return when (sortMethod) {
            SortMethod.Alpha -> if (descending) database.getTitleDescending(ids, offset, limit) else database.getTitleAscending(ids, offset, limit)
            SortMethod.Date -> if (descending) database.getDateDescending(ids, offset, limit) else database.getDateAscending(ids, offset, limit)
        }
    }
}

class ArchiveListServerSource(results: List<String>?,
                              sortMethod: SortMethod,
                              descending: Boolean,
                              isSearch: Boolean,
                              val totalSize: Int,
                              private val onlyNew: Boolean,
                              private val filter: CharSequence) : ArchiveDataSourceBase(sortMethod, descending, isSearch) {

    private val totalResults = mutableListOf<String>()
    override val searchResults: List<String>? = if (filter.isBlank() && !isSearch && !onlyNew) null else totalResults

    init {
        if (results != null)
            totalResults.addAll(results)
    }

    override fun loadRange(params: LoadRangeParams, callback: LoadRangeCallback<Archive>) {
        if (isSearch && filter.isBlank()) //Search mode with no search.  Display none.
            callback.onResult(emptyList())
        else if (filter.isBlank() && !onlyNew) { //This isn't search mode.  Display all.
            val archives = getArchives(null, params.startPosition, params.loadSize)
            callback.onResult(archives)
        } else {
            var endIndex = min(params.startPosition + params.loadSize, totalSize)
            if (endIndex <= totalResults.size) {
                val ids = totalResults.subList(params.startPosition, endIndex)
                val archives = getArchives(ids)
                callback.onResult(archives)
            } else {
                DatabaseReader.refreshListener?.isRefreshing(true)

                loadResults(endIndex)
                endIndex = min(params.startPosition + params.loadSize, totalResults.size)
                val ids = totalResults.subList(params.startPosition, endIndex)
                val archives = getArchives(ids)
                DatabaseReader.refreshListener?.isRefreshing(false)
                callback.onResult(archives)
            }
        }
    }

    private fun loadResults(endIndex: Int) {
        do {
            val newResults = WebHandler.searchServer(filter, onlyNew, sortMethod, descending, totalResults.size, false)

            if (newResults.results != null)
                totalResults.addAll(newResults.results)
        } while (totalResults.size < endIndex || newResults.results == null)
    }

    override fun loadInitial(params: LoadInitialParams, callback: LoadInitialCallback<Archive>) {
        if (isSearch && filter.isBlank())
            callback.onResult(emptyList(), 0, 0)
        else if (filter.isBlank() && !onlyNew) {
            val archives = getArchives(null, params.requestedStartPosition, params.requestedLoadSize)
            val archiveCount = database.archiveDao().getArchiveCount()
            callback.onResult(archives, params.requestedStartPosition, archiveCount)
        } else {
            var endIndex = min(params.requestedStartPosition + params.requestedLoadSize, totalSize)
            if (params.requestedStartPosition < totalResults.size && endIndex <= totalResults.size) {
                val ids = totalResults.subList(params.requestedStartPosition, endIndex)
                val archives = getArchives(ids)
                callback.onResult(archives, params.requestedStartPosition, totalSize)
            } else {
                DatabaseReader.refreshListener?.isRefreshing(true)
                loadResults(endIndex)
                endIndex = min(params.requestedStartPosition + params.requestedLoadSize, totalResults.size)
                val ids = totalResults.subList(params.requestedStartPosition, endIndex)
                val archives = getArchives(ids)
                DatabaseReader.refreshListener?.isRefreshing(false)
                callback.onResult(archives, params.requestedStartPosition, if (archives.size < ids.size) archives.size else totalSize)
            }
        }
    }

}

class ArchiveListDataSource(private val results: List<String>?,
                            sortMethod: SortMethod,
                            descending: Boolean,
                            isSearch: Boolean) : ArchiveDataSourceBase(sortMethod, descending, isSearch) {
    override val searchResults = results

    override fun loadRange(params: LoadRangeParams, callback: LoadRangeCallback<Archive>) {
        val ids = results?.let {
            val endIndex = min(params.startPosition + params.loadSize, it.size)
            it.subList(params.startPosition, endIndex)
        }
        val archives = if (ids != null) getArchives(ids) else getArchives(null, params.startPosition, params.loadSize)
        callback.onResult(archives)
    }

    override fun loadInitial(params: LoadInitialParams, callback: LoadInitialCallback<Archive>) {
        val ids = results?.let {
            val endIndex = min(params.requestedStartPosition + params.requestedLoadSize, it.size)
            it.subList(params.requestedStartPosition, endIndex)
        }

        val archives = if (ids != null) getArchives(ids) else getArchives(null, params.requestedStartPosition, params.requestedLoadSize)
        val totalSize = if (ids != null || !isSearch) results?.size ?: database.archiveDao().getArchiveCount() else 0
        callback.onResult(archives, params.requestedStartPosition, if (ids != null && archives.size < ids.size) archives.size else totalSize)
    }
}
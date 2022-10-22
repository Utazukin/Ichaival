/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2022 Utazukin
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
import kotlinx.coroutines.*
import java.lang.Integer.max
import kotlin.math.min

class ServerSearchResult(val results: List<String>?,
                         val totalSize: Int = 0,
                         val filter: CharSequence = "",
                         val onlyNew: Boolean = false)

abstract class ArchiveListDataFactoryBase : DataSource.Factory<Int, Archive>() {
    var currentSource: ArchiveDataSourceBase? = null
        private set
    protected val archiveLiveData = MutableLiveData<ArchiveDataSourceBase>()
    protected var results: List<String>? = null
    open fun updateSort(method: SortMethod, desc: Boolean, force: Boolean) {}
    open fun updateSearchResults(searchResults: List<String>?) {}
    open fun updateSearchResults(searchResult: ServerSearchResult) {}

    override fun create(): DataSource<Int, Archive> {
        val latestSource = createDataSource()
        currentSource = latestSource
        archiveLiveData.postValue(latestSource)
        return latestSource
    }

    protected abstract fun createDataSource() : ArchiveDataSourceBase

    fun reset() = archiveLiveData.value?.invalidate()
}

class RandomArchiveListDataFactory : ArchiveListDataFactoryBase() {
    override fun createDataSource() = RandomServerSource(results ?: emptyList())
    override fun updateSearchResults(searchResult: ServerSearchResult) {
        super.updateSearchResults(searchResult)
        results = searchResult.results
        archiveLiveData.value?.invalidate()
    }
}

class ArchiveListDataFactory(private val localSearch: Boolean) : ArchiveListDataFactoryBase() {
    var isSearch = false
    private var sortMethod = SortMethod.Alpha
    private var descending = false
    private var totalResultCount = 0
    private var onlyNew = false
    private var filter: CharSequence = ""

    override fun createDataSource() : ArchiveDataSourceBase {
        return if (localSearch)
            ArchiveListDataSource(results, sortMethod, descending, isSearch)
        else
            ArchiveListServerSource(results, sortMethod, descending, isSearch, totalResultCount, onlyNew, filter)
    }

    override fun updateSort(method: SortMethod, desc: Boolean, force: Boolean) {
        if (sortMethod != method || descending != desc || force) {
            sortMethod = method
            descending = desc

            if (!localSearch)
                results = null

            archiveLiveData.value?.invalidate()
        }
    }

    override fun updateSearchResults(searchResults: List<String>?) {
        results = searchResults
        archiveLiveData.value?.invalidate()
    }

    override fun updateSearchResults(searchResult: ServerSearchResult) {
        results = searchResult.results
        onlyNew = searchResult.onlyNew
        filter = searchResult.filter
        totalResultCount = searchResult.totalSize
        archiveLiveData.value?.invalidate()
    }
}

abstract class ArchiveDataSourceBase(protected val sortMethod: SortMethod,
                                     protected val descending: Boolean,
                                     protected val isSearch: Boolean) : PositionalDataSource<Archive>() {
    protected val database by lazy { DatabaseReader.database }
    abstract val searchResults: List<String>?

    protected open fun getArchives(ids: List<String>?, offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive> {
        if (isSearch && ids == null)
            return emptyList()

        return when (sortMethod) {
            SortMethod.Alpha -> if (descending) database.getTitleDescending(ids, offset, limit) else database.getTitleAscending(ids, offset, limit)
            SortMethod.Date -> if (descending) database.getDateDescending(ids, offset, limit) else database.getDateAscending(ids, offset, limit)
        }
    }

    protected fun getSubList(startIndex: Int, endIndex: Int, list: List<String>) = if (endIndex < startIndex) list else list.subList(startIndex, endIndex)
}

class RandomServerSource(results: List<String>) : ArchiveDataSourceBase(SortMethod.Alpha, false, true) {
    override val searchResults: List<String> = results
    private val totalSize = results.size

    override fun getArchives(ids: List<String>?, offset: Int, limit: Int): List<Archive> {
        return database.getArchives(ids ?: database.archiveDao().getAllIds(), offset, limit)
    }

    override fun loadInitial(params: LoadInitialParams, callback: LoadInitialCallback<Archive>) {
        val endIndex = min(params.requestedStartPosition + params.requestedLoadSize, totalSize)
        val ids = getSubList(params.requestedStartPosition, endIndex, searchResults)
        val archives = getArchives(ids)
        callback.onResult(archives, params.requestedStartPosition, totalSize)
    }

    override fun loadRange(params: LoadRangeParams, callback: LoadRangeCallback<Archive>) {
        val endIndex = min(params.startPosition + params.loadSize, totalSize)
        val ids = searchResults.subList(params.startPosition, endIndex)
        val archives = getArchives(ids)
        callback.onResult(archives)
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
                WebHandler.updateRefreshing(true)

                runBlocking { loadResults(endIndex) }
                endIndex = min(params.startPosition + params.loadSize, totalResults.size)
                val ids = totalResults.subList(params.startPosition, endIndex)
                val archives = getArchives(ids)
                WebHandler.updateRefreshing(false)
                callback.onResult(archives)
            }
        }
    }

    private suspend fun loadResults(endIndex: Int) = coroutineScope {
        val remaining = endIndex - totalResults.size
        val currentSize = totalResults.size
        val pages = remaining.floorDiv(ServerManager.pageSize)
        val jobs = buildList(pages + 1) {
            for (i in 0 until pages) {
                val job = async(Dispatchers.IO) { WebHandler.searchServer(filter, onlyNew, sortMethod, descending, currentSize + i * ServerManager.pageSize, false) }
                add(job)
            }

            val job = async(Dispatchers.IO) { WebHandler.searchServer(filter, onlyNew, sortMethod, descending, currentSize + pages * ServerManager.pageSize, false) }
            add(job)
        }


        totalResults.addAll(jobs.awaitAll().mapNotNull { it.results }.flatten())
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
                val ids = getSubList(params.requestedStartPosition, endIndex, totalResults)
                val archives = getArchives(ids)
                val size = if (archives.size < ids.size && totalSize < params.requestedLoadSize) archives.size else totalSize
                callback.onResult(archives, params.requestedStartPosition, size)
            } else {
                WebHandler.updateRefreshing(true)
                runBlocking { loadResults(endIndex) }
                endIndex = min(params.requestedStartPosition + params.requestedLoadSize, totalResults.size)
                val ids = getSubList(params.requestedStartPosition, endIndex, totalResults)
                val archives = getArchives(ids)
                WebHandler.updateRefreshing(false)
                var startIndex = params.requestedStartPosition
                while (startIndex > 0 && startIndex >= totalSize)
                    startIndex = max(0, startIndex - params.requestedLoadSize)
                callback.onResult(archives, startIndex, if (archives.size < ids.size) archives.size else totalSize)
            }
        }
    }

}

class ArchiveListDataSource(results: List<String>?,
                            sortMethod: SortMethod,
                            descending: Boolean,
                            isSearch: Boolean) : ArchiveDataSourceBase(sortMethod, descending, isSearch) {
    override val searchResults = results

    override fun loadRange(params: LoadRangeParams, callback: LoadRangeCallback<Archive>) {
        val ids = searchResults?.let {
            val endIndex = min(params.startPosition + params.loadSize, it.size)
            it.subList(params.startPosition, endIndex)
        }
        val archives = if (ids != null) getArchives(ids) else getArchives(null, params.startPosition, params.loadSize)
        callback.onResult(archives)
    }

    override fun loadInitial(params: LoadInitialParams, callback: LoadInitialCallback<Archive>) {
        val ids = searchResults?.let {
            val endIndex = min(params.requestedStartPosition + params.requestedLoadSize, it.size)
            getSubList(params.requestedStartPosition, endIndex, it)
        }

        val archives = if (ids != null) getArchives(ids) else getArchives(null, params.requestedStartPosition, params.requestedLoadSize)
        val totalSize = if (ids != null || !isSearch) searchResults?.size ?: database.archiveDao().getArchiveCount() else 0
        val startPosition = if (params.requestedStartPosition + archives.size > archives.size) 0 else params.requestedStartPosition
        callback.onResult(archives, startPosition, if (ids != null && archives.size < ids.size) archives.size else totalSize)
    }
}
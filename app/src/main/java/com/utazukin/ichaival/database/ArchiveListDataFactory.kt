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

import androidx.lifecycle.MutableLiveData
import androidx.paging.DataSource
import androidx.paging.PositionalDataSource
import com.utazukin.ichaival.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
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
            ArchiveListDataSource(results, sortMethod, descending, isSearch, filter)
        else
            ArchiveListServerSource(results, sortMethod, descending, isSearch, filter, totalResultCount, onlyNew)
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
                                     protected val isSearch: Boolean,
                                     protected val filter: CharSequence) : PositionalDataSource<Archive>() {
    protected val database by lazy { DatabaseReader.database }
    abstract val searchResults: List<String>?
    private var titleSortResult: List<String>? = null

    protected fun getSortedResults(ids: List<String>? = null) : List<String> {
        titleSortResult?.let { return it }

        val archives = database.getTitleSort(ids)
        val comparer = object : Comparator<TitleSortArchive> {
            val naturalComparer = NaturalOrderComparator()
            override fun compare(a: TitleSortArchive, b: TitleSortArchive) : Int {
                return if (descending) naturalComparer.compare(b.title, a.title) else naturalComparer.compare(a.title, b.title)
            }
        }

        return archives.sortedWith(comparer).map { it.id }.also { titleSortResult = it }
    }

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

class RandomServerSource(results: List<String>) : ArchiveDataSourceBase(SortMethod.Alpha, false, true, "") {
    override val searchResults: List<String> = results
    private val totalSize = results.size

    override fun getArchives(ids: List<String>?, offset: Int, limit: Int): List<Archive> {
        return database.getArchives(ids ?: database.archiveDao().getAllIds(), offset, limit)
    }

    override fun loadInitial(params: LoadInitialParams, callback: LoadInitialCallback<Archive>) {
        val start = computeInitialLoadPosition(params, totalSize)
        val size = computeInitialLoadSize(params, start, totalSize)
        val endIndex = min(start + size, totalSize)
        val ids = getSubList(start, endIndex, searchResults)
        val archives = getArchives(ids)
        callback.onResult(archives, start, totalSize)
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
                              filter: CharSequence,
                              val totalSize: Int,
                              private val onlyNew: Boolean) : ArchiveDataSourceBase(sortMethod, descending, isSearch, filter) {

    private val totalResults = mutableListOf<String>()
    override val searchResults: List<String>? = if (filter.isBlank() && !isSearch && !onlyNew) null else totalResults

    init {
        if (results != null)
            totalResults.addAll(results)
    }

    override fun getArchives(ids: List<String>?, offset: Int, limit: Int): List<Archive> {
        return when {
            sortMethod == SortMethod.Alpha && (ids == null || filter == STATIC_CATEGORY_SEARCH) -> database.getArchives(getSortedResults(ids), offset, limit)
            searchResults == null || ids == null || filter == STATIC_CATEGORY_SEARCH -> super.getArchives(ids, offset, limit)
            else -> database.getArchives(ids, offset, limit)
        }
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
                val job = async { WebHandler.searchServer(filter, onlyNew, sortMethod, descending, currentSize + i * ServerManager.pageSize, false) }
                add(job)
            }

            val job = async { WebHandler.searchServer(filter, onlyNew, sortMethod, descending, currentSize + pages * ServerManager.pageSize, false) }
            add(job)
        }


        totalResults.addAll(jobs.awaitAll().mapNotNull { it.results }.flatten())
    }

    override fun loadInitial(params: LoadInitialParams, callback: LoadInitialCallback<Archive>) {
        if (isSearch && filter.isBlank())
            callback.onResult(emptyList(), 0, 0)
        else if (filter.isBlank() && !onlyNew) {
            val archiveCount = database.archiveDao().getArchiveCount()
            val start = computeInitialLoadPosition(params, archiveCount)
            val size = computeInitialLoadSize(params, start, archiveCount)
            val archives = getArchives(null, start, size)
            callback.onResult(archives, start, archiveCount)
        } else {
            val start = computeInitialLoadPosition(params, totalSize)
            val size = computeInitialLoadSize(params, start, totalSize)
            var endIndex = min(start + size, totalSize)
            if (start < totalResults.size && endIndex <= totalResults.size) {
                val ids = getSubList(start, endIndex, totalResults)
                val archives = getArchives(ids)
                val count = if (archives.size != totalSize && archives.size % params.requestedLoadSize != 0) archives.size else totalSize
                callback.onResult(archives, start, count)
            } else {
                WebHandler.updateRefreshing(true)
                runBlocking { loadResults(endIndex) }
                endIndex = min(start + size, totalResults.size)
                val ids = getSubList(start, endIndex, totalResults)
                val archives = getArchives(ids)
                val count = if (archives.size != totalSize && archives.size % params.requestedLoadSize != 0) archives.size else totalSize
                WebHandler.updateRefreshing(false)
                callback.onResult(archives, start, count)
            }
        }
    }

}

class ArchiveListDataSource(results: List<String>?,
                            sortMethod: SortMethod,
                            descending: Boolean,
                            isSearch: Boolean,
                            filter: CharSequence) : ArchiveDataSourceBase(sortMethod, descending, isSearch, filter) {
    override val searchResults = results

    override fun loadRange(params: LoadRangeParams, callback: LoadRangeCallback<Archive>) {
        val ids = searchResults?.let {
            val endIndex = min(params.startPosition + params.loadSize, it.size)
            it.subList(params.startPosition, endIndex)
        }
        val archives = if (ids != null) getArchives(ids) else getArchives(null, params.startPosition, params.loadSize)
        callback.onResult(archives)
    }

    override fun getArchives(ids: List<String>?, offset: Int, limit: Int): List<Archive> {
        if (ids == null)
            return super.getArchives(null, offset, limit)

        return database.getArchives(ids, offset, limit)
    }

    override fun loadInitial(params: LoadInitialParams, callback: LoadInitialCallback<Archive>) {
        val ids = searchResults?.let {
            val sortedResult = getSortedResults(it)
            val endIndex = min(params.requestedStartPosition + params.requestedLoadSize, sortedResult.size)
            getSubList(params.requestedStartPosition, endIndex, sortedResult)
        }

        val totalSize = if (ids != null || !isSearch) searchResults?.size ?: database.archiveDao().getArchiveCount() else 0
        val startPosition = computeInitialLoadPosition(params, totalSize)
        val size = computeInitialLoadSize(params, startPosition, totalSize)
        val archives = if (ids != null) getArchives(ids) else getArchives(null, startPosition, size)
        val count = if (archives.size != totalSize && archives.size % params.requestedLoadSize != 0) archives.size else totalSize
        callback.onResult(archives, startPosition, count)
    }
}
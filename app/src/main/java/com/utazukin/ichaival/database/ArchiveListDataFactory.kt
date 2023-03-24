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

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.utazukin.ichaival.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.min

data class ServerSearchResult(val results: List<String>?,
                         val totalSize: Int = 0,
                         val filter: CharSequence = "",
                         val onlyNew: Boolean = false)

abstract class ArchiveListPagingSourceBase : PagingSource<Int, Archive>() {
    var totalSize = 0
        protected set
    var results: List<String>? = null
        protected set
    var isSearch = false
    protected var onlyNew = false
    protected var sortMethod = SortMethod.Alpha
    protected var descending = false
    protected var filter: CharSequence = ""
    protected val database
        get() = DatabaseReader.database
    private var titleSortResult: List<String>? = null
    override val jumpingSupported = true

    private suspend fun getSortedResults(ids: List<String>? = null) : List<String> {
        titleSortResult?.let {
            if (ids == null || it.size >= ids.size)
                return it
        }

        val comparer = object : Comparator<TitleSortArchive> {
            val naturalComparer = NaturalOrderComparator()
            override fun compare(a: TitleSortArchive, b: TitleSortArchive) : Int {
                return if (descending) naturalComparer.compare(b.title, a.title) else naturalComparer.compare(a.title, b.title)
            }
        }

        val archives = database.getTitleSort(ids).apply { sortWith(comparer) }
        return archives.map { it.id }.also { titleSortResult = it }
    }

    protected open suspend fun getArchives(ids: List<String>?, offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive> {
        if (isSearch && ids == null)
            return emptyList()

        return when (sortMethod) {
            SortMethod.Alpha -> database.getArchives(getSortedResults(ids), offset, limit)
            SortMethod.Date -> if (descending) database.getDateDescending(ids, offset, limit) else database.getDateAscending(ids, offset, limit)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Archive>) = state.anchorPosition
    open fun updateSort(method: SortMethod, desc: Boolean, force: Boolean) : Boolean {
        if (sortMethod != method || descending != desc || force) {
            sortMethod = method
            descending = desc

            return true
        }

        return false
    }
    fun updateSearchResults(searchResults: List<String>?) {
        results = searchResults
    }
    fun updateSearchResults(searchResult: ServerSearchResult) {
        results = searchResult.results
        onlyNew = searchResult.onlyNew
        filter = searchResult.filter
        totalSize = searchResult.totalSize
    }

    abstract fun copy() : ArchiveListPagingSourceBase
    protected open fun copyTo(copy: ArchiveListPagingSourceBase) {
        copy.results = results
        copy.isSearch = isSearch
        copy.totalSize = totalSize
        copy.onlyNew = onlyNew
        copy.filter = filter
        copy.sortMethod = sortMethod
        copy.descending = descending
    }
}

class ArchiveListPagingSourceServer : ArchiveListPagingSourceBase() {
    private val totalResults: MutableList<String> = mutableListOf()

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

    private fun computeNextKey(position: Int, loadSize: Int, total: Int) : Int? {
        val next = position + loadSize
        return if (next >= total) null else next
    }

    override suspend fun getArchives(ids: List<String>?, offset: Int, limit: Int): List<Archive> {
        if (sortMethod == SortMethod.Alpha && ids != null)
            return database.getArchives(ids, offset, limit)

        return super.getArchives(ids, offset, limit)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Archive> {
        val position = if (params is LoadParams.Refresh) 0 else params.key ?: 0
        val prev = if (position > 0) position - 1 else null
        return if (isSearch && filter.isBlank()) //Search mode with no search.  Display none.
            LoadResult.Page(emptyList(), null, null)
        else if (filter.isBlank() && !onlyNew) { //This isn't search mode.  Display all.
            val archiveCount = database.archiveDao().getArchiveCount()
            val archives = getArchives(null, position, params.loadSize)
            val next = computeNextKey(position, params.loadSize, archiveCount)
            LoadResult.Page(archives, prev, next, position, next?.let { archiveCount - it } ?: 0)
        } else {
            val endIndex = min(position + params.loadSize, totalSize)
            val next = if (endIndex >= totalSize) null else endIndex
            if (endIndex <= totalResults.size) {
                val archives = getArchives(totalResults, position, params.loadSize)
                LoadResult.Page(archives, prev, next, prev?.plus(1) ?: 0, next?.let { totalSize - it } ?: 0)
            } else {
                WebHandler.updateRefreshing(true)

                loadResults(endIndex)
                val archives = getArchives(totalResults, position, params.loadSize)
                WebHandler.updateRefreshing(false)
                LoadResult.Page(archives, prev, next, prev?.plus(1) ?: 0, next?.let { totalSize - it } ?: 0)
            }
        }
    }

    override fun copy(): ArchiveListPagingSourceBase {
        val copy = ArchiveListPagingSourceServer()
        copyTo(copy)
        results?.let { totalResults.addAll(it) }
        return copy
    }

}

class ArchiveListPagingSourceRandom : ArchiveListPagingSourceBase() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Archive> {
        val position = if (params is LoadParams.Refresh) 0 else params.key ?: 0
        val endIndex = min(position + params.loadSize, totalSize)
        val ids = results?.subList(position, endIndex)
        val archives = getArchives(ids)
        val prev = if (position > 0) position - 1 else null
        val next = if (position + params.loadSize >= totalSize) null else position + params.loadSize
        return LoadResult.Page(archives, prev, next, prev ?: 0, next?.let { totalSize - it } ?: 0)
    }

    override fun copy(): ArchiveListPagingSourceBase {
        val copy = ArchiveListPagingSourceRandom()
        copyTo(copy)
        return copy
    }
}

class ArchiveListPagingSourceLocal : ArchiveListPagingSourceBase() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Archive> {
        val position = if (params is LoadParams.Refresh) 0 else params.key ?: 0
        val totalSize = if (results != null || !isSearch) results?.size ?: database.archiveDao().getArchiveCount() else 0
        val archives = getArchives(results, position, params.loadSize)
        val prev = if (position > 0) position - 1 else null
        val next = if (position + params.loadSize >= totalSize) null else position + params.loadSize
        return LoadResult.Page(archives, prev, next, prev ?: 0, next?.let { totalSize - it } ?: 0)
    }

    override fun copy(): ArchiveListPagingSourceBase {
        val copy = ArchiveListPagingSourceLocal()
        copyTo(copy)
        return copy
    }
}
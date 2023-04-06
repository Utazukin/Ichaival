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
import androidx.room.withTransaction
import com.utazukin.ichaival.Archive
import com.utazukin.ichaival.SortMethod
import com.utazukin.ichaival.WebHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.math.min

data class ServerSearchResult(val results: List<String>?,
                         val totalSize: Int = 0,
                         val filter: CharSequence = "",
                         val onlyNew: Boolean = false)

class EmptySource<Key : Any, Value : Any> : PagingSource<Key, Value>() {
    override fun getRefreshKey(state: PagingState<Key, Value>) = null
    override suspend fun load(params: LoadParams<Key>): LoadResult<Key, Value> = LoadResult.Page(emptyList(), null, null)
}

open class ArchiveListServerPagingSource(
    private val isSearch: Boolean,
    private val onlyNew: Boolean,
    private val sortMethod: SortMethod,
    private val descending: Boolean,
    protected val filter: String,
    protected val database: ArchiveDatabase
) : PagingSource<Int, Archive>() {
    protected val totalResults = mutableListOf<String>()
    var totalSize = -1
        protected set
    private val coroutineContext = Dispatchers.IO + SupervisorJob()
    private val roomSource = database.getArchiveSearchSource(filter, sortMethod, descending, onlyNew)

    init {
        registerInvalidatedCallback {
            coroutineContext.cancel()
            roomSource.invalidate()
        }
    }

    protected suspend fun getArchives(ids: List<String>?, offset: Int = 0, limit: Int = Int.MAX_VALUE): List<Archive> {
        if (isSearch && ids == null)
            return emptyList()

        return database.getArchives(ids, sortMethod, descending, offset, limit, onlyNew)
    }

    override fun getRefreshKey(state: PagingState<Int, Archive>) = state.anchorPosition

    private suspend fun loadResults() {
        val cacheCount = database.archiveDao().getCachedSearchCount(filter)
        if (cacheCount == 0) {
            WebHandler.updateRefreshing(true)
            val results = WebHandler.searchServer(filter, false, sortMethod, descending, -1, false)
            totalSize = results.totalSize
            results.results?.let {
                database.withTransaction {
                    for (result in it)
                        database.archiveDao().insertSearch(SearchArchiveRef(filter, result))
                }
            }
            WebHandler.updateRefreshing(false)
        } else totalSize = cacheCount
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Archive> {
        loadResults()
        return roomSource.load(params)
    }
}

class ArchiveListRandomPagingSource(filter: String, private val count: UInt, private val categoryId: String?, database: ArchiveDatabase)
    : ArchiveListServerPagingSource(false, false, SortMethod.Alpha, false, filter, database) {
    private suspend fun loadResults() {
        val result = WebHandler.getRandomArchives(count, filter, categoryId)
        totalSize = result.totalSize
        result.results?.let { totalResults.addAll(it) }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Archive> {
        val position = if (params is LoadParams.Refresh) 0 else params.key ?: 0
        val prev = if (position > 0) position - 1 else null
        if (totalResults.isEmpty())
            loadResults()

        val endIndex = min(position + params.loadSize, totalSize)
        val next = if (endIndex >= totalSize) null else endIndex
        val archives = if (totalResults.isEmpty()) emptyList() else getArchives(totalResults, position, params.loadSize)
        return LoadResult.Page(archives, prev, next, prev?.plus(1) ?: 0, next?.let { totalSize - it } ?: 0)
    }
}
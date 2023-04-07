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

import android.util.JsonReader
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.withTransaction
import com.utazukin.ichaival.Archive
import com.utazukin.ichaival.SortMethod
import com.utazukin.ichaival.WebHandler
import com.utazukin.ichaival.parseTermsInfo

data class ServerSearchResult(val results: List<String>?,
                         val totalSize: Int = 0,
                         val filter: CharSequence = "",
                         val onlyNew: Boolean = false)

class EmptySource<Key : Any, Value : Any> : PagingSource<Key, Value>() {
    override fun getRefreshKey(state: PagingState<Key, Value>) = null
    override suspend fun load(params: LoadParams<Key>): LoadResult<Key, Value> = LoadResult.Page(emptyList(), null, null)
}

abstract class ArchiveListPagingSourceBase(protected val filter: String,
                                           protected val sortMethod: SortMethod,
                                           protected val descending: Boolean,
                                           onlyNew: Boolean,
                                           protected val database: ArchiveDatabase) : PagingSource<Int, Archive>() {
   var totalSize = -1
       protected set
    protected open val roomSource = database.getArchiveSearchSource(filter, sortMethod, descending, onlyNew)

    init {
        registerInvalidatedCallback { roomSource.invalidate() }
    }
}

open class ArchiveListServerPagingSource(
    onlyNew: Boolean,
    sortMethod: SortMethod,
    descending: Boolean,
    filter: String,
    database: ArchiveDatabase
) : ArchiveListPagingSourceBase(filter, sortMethod, descending, onlyNew, database) {
    override fun getRefreshKey(state: PagingState<Int, Archive>) = state.anchorPosition

    protected open suspend fun loadResults() {
        val cacheCount = database.archiveDao().getCachedSearchCount(filter)
        if (cacheCount == 0) {
            WebHandler.updateRefreshing(true)
            val resultsStream = WebHandler.searchServerRaw(filter, false, sortMethod, descending, -1)
            totalSize = 0
            resultsStream?.use {
                JsonReader(it.bufferedReader(Charsets.UTF_8)).use { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "resultsFiltered" -> totalSize = reader.nextInt()
                            "data" -> {
                                database.withTransaction {
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        if (reader.nextName() == "arcid")
                                            database.archiveDao().insertSearch(SearchArchiveRef(filter, reader.nextString()))
                                        else reader.skipValue()
                                    }
                                    reader.endArray()
                                }
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
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

class ArchiveListLocalPagingSource(filter: String,
                                   sortMethod: SortMethod,
                                   descending: Boolean,
                                   onlyNew: Boolean,
                                   database: ArchiveDatabase) : ArchiveListPagingSourceBase(filter, sortMethod, descending, onlyNew, database) {
    override fun getRefreshKey(state: PagingState<Int, Archive>) = state.anchorPosition

    private suspend fun internalFilter() {
        WebHandler.updateRefreshing(true)
        database.withTransaction {
            val totalCount = database.archiveDao().getArchiveCount()
            val terms = parseTermsInfo(filter)
            val titleSearch = filter.removeSurrounding("\"")
            for (i in 0 until totalCount step DatabaseReader.MAX_WORKING_ARCHIVES) {
                val allArchives = database.archiveDao().getArchives(i, DatabaseReader.MAX_WORKING_ARCHIVES)
                for (archive in allArchives) {
                    if (archive.title.contains(titleSearch, ignoreCase = true)) {
                        database.archiveDao().insertSearch(SearchArchiveRef(filter, archive.id))
                        ++totalSize
                    } else {
                        for (termInfo in terms) {
                            if (archive.containsTag(termInfo.term, termInfo.exact) != termInfo.negative) {
                                database.archiveDao().insertSearch(SearchArchiveRef(filter, archive.id))
                                ++totalSize
                                break
                            }
                        }
                    }
                }
            }
        }
        WebHandler.updateRefreshing(false)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Archive> {
        val cacheCount = database.archiveDao().getCachedSearchCount(filter)
        if (cacheCount == 0)
            internalFilter()
        return roomSource.load(params)
    }
}

class ArchiveListRandomPagingSource(filter: String, private val count: UInt, private val categoryId: String, database: ArchiveDatabase)
    : ArchiveListServerPagingSource(false, SortMethod.Alpha, false, filter, database) {
    override val roomSource =  when {
        categoryId.isNotEmpty() -> database.archiveDao().getRandomCategorySource(categoryId, count.toInt())
        filter.isNotEmpty() -> database.archiveDao().getSearchResultsRandom(filter, count.toInt())
        else -> database.archiveDao().getRandomSource(count.toInt())
    }

    override suspend fun loadResults() {
        if (filter.isNotEmpty() && categoryId.isEmpty())
            super.loadResults()
        else
            totalSize = count.toInt()
    }
}
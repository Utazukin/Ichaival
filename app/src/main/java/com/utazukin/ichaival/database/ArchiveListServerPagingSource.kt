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
import com.utazukin.ichaival.*

data class ServerSearchResult(val results: List<String>?,
                              val totalSize: Int = 0,
                              val filter: CharSequence = "",
                              val onlyNew: Boolean = false)

class EmptySource<Key : Any, Value : Any> : PagingSource<Key, Value>() {
    override val jumpingSupported = true
    override fun getRefreshKey(state: PagingState<Key, Value>) = null
    override suspend fun load(params: LoadParams<Key>): LoadResult<Key, Value> = LoadResult.Page(emptyList(), null, null)
}

abstract class ArchiveListPagingSourceBase(protected val filter: String,
                                           protected val sortMethod: SortMethod,
                                           protected val descending: Boolean,
                                           onlyNew: Boolean) : PagingSource<Int, ArchiveBase>() {
    protected open val roomSource = DatabaseReader.getArchiveSearchSource(filter, sortMethod, descending, onlyNew)
    override val jumpingSupported get() = roomSource.jumpingSupported

    override fun getRefreshKey(state: PagingState<Int, ArchiveBase>) = roomSource.getRefreshKey(state)
    protected suspend fun insertSearch(arcId: String) = DatabaseReader.insertSearch(SearchArchiveRef(filter, arcId))
    fun reset() {
        invalidate()
        roomSource.invalidate()
    }
}

open class ArchiveListServerPagingSource(
    onlyNew: Boolean,
    sortMethod: SortMethod,
    descending: Boolean,
    filter: String) : ArchiveListPagingSourceBase(filter, sortMethod, descending, onlyNew) {
    protected open suspend fun loadResults() {
        val cacheCount = DatabaseReader.getCachedSearchCount(filter)
        if (cacheCount == 0) {
            WebHandler.updateRefreshing(true)
            val resultsStream = WebHandler.searchServerRaw(filter, false, sortMethod, descending, -1)
            resultsStream?.use {
                JsonReader(it.bufferedReader(Charsets.UTF_8)).use { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "data" -> {
                                DatabaseReader.withTransaction {
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        reader.beginObject()
                                        while (reader.hasNext()) {
                                            if (reader.nextName() == "arcid")
                                                insertSearch(reader.nextString())
                                            else reader.skipValue()
                                        }
                                        reader.endObject()
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
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ArchiveBase> {
        loadResults()
        return roomSource.load(params)
    }
}

class ArchiveListLocalPagingSource(filter: String,
                                   sortMethod: SortMethod,
                                   descending: Boolean,
                                   onlyNew: Boolean) : ArchiveListPagingSourceBase(filter, sortMethod, descending, onlyNew) {
    private suspend fun internalFilter() {
        WebHandler.updateRefreshing(true)
        DatabaseReader.withTransaction {
            val totalCount = DatabaseReader.getArchiveCount()
            val terms = parseTermsInfo(filter)
            val titleSearch = filter.removeSurrounding("\"")
            for (i in 0 until totalCount step DatabaseReader.MAX_WORKING_ARCHIVES) {
                val allArchives = DatabaseReader.getArchives(i, DatabaseReader.MAX_WORKING_ARCHIVES)
                for (archive in allArchives) {
                    if (archive.title.contains(titleSearch, ignoreCase = true))
                        insertSearch(archive.id)
                    else if (terms.all { archive.containsTag(it.term, it.exact) != it.negative })
                        insertSearch(archive.id)
                }
            }
        }
        WebHandler.updateRefreshing(false)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ArchiveBase> {
        val cacheCount = DatabaseReader.getCachedSearchCount(filter)
        if (cacheCount == 0)
            internalFilter()
        return roomSource.load(params)
    }
}

class ArchiveListRandomPagingSource(filter: String, count: Int, private val categoryId: String)
    : ArchiveListServerPagingSource(false, SortMethod.Alpha, false, filter) {
    override val roomSource = DatabaseReader.getRandomSource(filter, categoryId, count)

    override suspend fun loadResults() {
        if (filter.isNotEmpty() && categoryId.isEmpty())
            super.loadResults()
    }
}
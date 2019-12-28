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

import androidx.lifecycle.*
import androidx.paging.DataSource
import androidx.paging.LivePagedListBuilder
import androidx.paging.PagedList
import androidx.paging.PositionalDataSource
import kotlinx.coroutines.Dispatchers
import kotlin.math.min

private const val SQL_LIST_LIMIT = 999

private fun <T, TT> DataSource.Factory<T, TT>.toLiveData(pageSize: Int = 50) = LivePagedListBuilder(this, pageSize).build()

class ArchiveListDataFactory : DataSource.Factory<Int, Archive>() {
    private val archiveLiveData = MutableLiveData<ArchiveListDataSource>()
    private var results: List<String>? = null
    private var sortMethod = SortMethod.Alpha
    private var descending = false

    override fun create(): DataSource<Int, Archive> {
        val latestSource = ArchiveListDataSource(results, sortMethod, descending)
        archiveLiveData.postValue(latestSource)
        return latestSource
    }

    fun updateSort(method: SortMethod, desc: Boolean) {
        sortMethod = method
        descending = desc
        archiveLiveData.value?.invalidate()
    }

    fun updateSearchResults(searchResults: List<String>?) {
        results = searchResults
        archiveLiveData.value?.invalidate()
    }

    fun updateSortAndResults(searchResults: List<String>?, method: SortMethod, desc: Boolean) {
        sortMethod = method
        descending = desc
        results = searchResults
        archiveLiveData.value?.invalidate()
    }

}

class ArchiveListDataSource(private val results: List<String>?,
                            private val sortMethod: SortMethod,
                            private val descending: Boolean) : PositionalDataSource<Archive>() {

    private val archiveDao by lazy { DatabaseReader.database.archiveDao() }

    override fun loadRange(params: LoadRangeParams, callback: LoadRangeCallback<Archive>) {
        val endIndex = min(params.startPosition + params.loadSize, results?.size ?: 0)
        val ids = results?.subList(params.startPosition, endIndex)
        val archives = getArchives(ids)
        callback.onResult(archives)
    }

    override fun loadInitial(params: LoadInitialParams, callback: LoadInitialCallback<Archive>) {
        val ids = results?.let {
            if (it.size < SQL_LIST_LIMIT)
                it
            else
                it.subList(params.requestedStartPosition, min(it.size, params.requestedLoadSize))
        }

        val archives = getArchives(ids)
        callback.onResult(archives, params.requestedStartPosition, ids?.size ?: archives.size)
    }

    private fun getArchives(ids: List<String>?) : List<Archive> {
        return  when (sortMethod) {
            SortMethod.Alpha -> {
                if (descending) {
                    if (ids == null)
                        archiveDao.getTitleDescending()
                    else
                        archiveDao.getTitleDescending(ids)
                } else {
                    if (ids == null)
                        archiveDao.getTitleAscending()
                    else
                        archiveDao.getTitleAscending(ids)
                }
            }
            SortMethod.Date -> {
                if (descending) {
                    if (ids == null)
                        archiveDao.getDateDescending()
                    else
                        archiveDao.getDateDescending(ids)
                } else {
                    if (ids == null)
                        archiveDao.getDateAscending()
                    else
                        archiveDao.getDateAscending(ids)
                }
            }
        }
    }
}

class ReaderTabViewModel : ViewModel() {
    val bookmarks = DatabaseReader.database.archiveDao().getDataBookmarks().toLiveData(5)
}

abstract class SearchViewModelBase : ViewModel() {
    abstract val archiveList: LiveData<PagedList<Archive>>
    protected val archiveDao by lazy { DatabaseReader.database.archiveDao() }
    protected val archiveDataFactory = ArchiveListDataFactory()
    protected var sortMethod = SortMethod.Alpha
    protected var descending = false

    abstract suspend fun getRandom(excludeBookmarked: Boolean = true): Archive?
    abstract fun updateSort(method: SortMethod, desc: Boolean, force: Boolean = false)
}

class SearchViewModel : SearchViewModelBase() {
    override lateinit var archiveList: LiveData<PagedList<Archive>>
    private var searchResults: List<String>? = null

    override suspend fun getRandom(excludeBookmarked: Boolean): Archive? {
        var data: Collection<String> = searchResults ?: archiveDao.getAllIds()

        if (excludeBookmarked)
            data = data.subtract(archiveDao.getBookmarks().map { it.id })

        val randId = if (data.isNotEmpty()) data.random() else null
        return if (randId != null) archiveDao.getArchive(randId) else null
    }

    fun init(method: SortMethod = SortMethod.Alpha, desc: Boolean = false, isSearch: Boolean = false) {
        if (this::archiveList.isInitialized)
            return

        archiveDataFactory.updateSort(method, desc)
        archiveList = archiveDataFactory.toLiveData()
    }

    fun filter(results: List<String>?) {
        searchResults = results
        archiveDataFactory.updateSearchResults(results)
    }

    override fun updateSort(method: SortMethod, desc: Boolean, force: Boolean) {
        if (method != sortMethod || descending != desc || force) {
            sortMethod = method
            descending = desc
            archiveDataFactory.updateSort(method, desc)
        }
    }
}

class ArchiveViewModel : SearchViewModelBase() {
    override lateinit var archiveList: LiveData<PagedList<Archive>>
    private val mutableSortData =
        MutableLiveData<Pair<Pair<SortMethod, Boolean>, Pair<CharSequence, Boolean>>>()
    private var sortFilter: CharSequence = ""
    private var onlyNew = false

    override suspend fun getRandom(excludeBookmarked: Boolean) : Archive? {
        var data: Collection<String> = internalFilter(sortFilter, onlyNew) ?: archiveDao.getAllIds()

        if (excludeBookmarked)
            data = data.subtract(archiveDao.getBookmarks().map { it.id })

        val randId = data.random()
        return archiveDao.getArchive(randId)
    }

    fun init(method: SortMethod = SortMethod.Alpha, desc: Boolean = false, filter: CharSequence = "", onlyNew: Boolean = false) {
        if (!this::archiveList.isInitialized) {
            mutableSortData.value = Pair(Pair(method, desc), Pair(filter, onlyNew))
            archiveList = Transformations.switchMap(mutableSortData) {
                liveData(Dispatchers.IO) {
                    val ids = internalFilter(it.second.first, it.second.second)
                    val data = when (it.first.first) {
                        SortMethod.Alpha -> {
                            if (it.first.second) {
                                if (ids != null)
                                    archiveDao.getDataTitleDescending(ids).toLiveData()
                                else
                                    archiveDao.getDataTitleDescending().toLiveData()
                            } else {
                                if (ids != null)
                                    archiveDao.getDataTitleAscending(ids).toLiveData()
                                else
                                    archiveDao.getDataTitleAscending().toLiveData()
                            }
                        }
                        SortMethod.Date -> {
                            if (it.first.second) {
                                if (ids != null)
                                    archiveDao.getDataDateDescending(ids).toLiveData()
                                else
                                    archiveDao.getDataDateDescending().toLiveData()
                            } else {
                                if (ids != null)
                                    archiveDao.getDataDateAscending(ids).toLiveData()
                                else
                                    archiveDao.getDataDateAscending().toLiveData()
                            }
                        }
                    }
                    emitSource(data)
                }
            }
        }
    }

    fun filter(filter: CharSequence?, onlyNew: Boolean) {
        sortFilter = filter ?: ""
        this.onlyNew = onlyNew
        mutableSortData.value = Pair(Pair(sortMethod, descending), Pair(sortFilter, onlyNew))
    }

    private fun internalFilter(filter: CharSequence?, onlyNew: Boolean) : List<String>? {
        val mValues = mutableListOf<String>()
        if (filter == null)
            return mValues

        fun addIfNew(archive: Archive) {
            if (!onlyNew || archive.isNew)
                mValues.add(archive.id)
        }

        val allArchives = archiveDao.getAll()
        if (filter.isEmpty())
            return if (onlyNew) allArchives.filter { it.isNew }.map { it.id } else null
        else {
            val normalized = filter.toString().toLowerCase()
            val spaceRegex by lazy { Regex("\\s") }
            for (archive in allArchives) {
                if (archive.title.toLowerCase().contains(normalized) && !mValues.contains(archive.id))
                    addIfNew(archive)
                else {
                    val terms = filter.split(spaceRegex)
                    var hasAll = true
                    var i = 0
                    while (i < terms.size) {
                        var term = terms[i]
                        val colonIndex = term.indexOf(':')
                        if (term.startsWith("\"")
                            || (colonIndex in 0..(term.length - 2) && term[colonIndex + 1] == '"')) {
                            val builder = StringBuilder(term)
                            if (!term.endsWith("\"")) {
                                var k = i + 1
                                while (k < terms.size && !terms[k].endsWith("\"")) {
                                    builder.append(" ")
                                    builder.append(terms[k])
                                    ++k
                                }

                                if (k < terms.size && terms[k].endsWith("\"")) {
                                    builder.append(" ")
                                    builder.append(terms[k])
                                }
                                i = k
                            }
                            term = builder.removeSurrounding("\"").toString()
                        }

                        val containsTag = archive.containsTag(term.removePrefix("-"))
                        val isNegative = term.startsWith("-")
                        if (containsTag == isNegative) {
                            hasAll = false
                            break
                        }
                        ++i
                    }

                    if (hasAll && !mValues.contains(archive.id))
                        addIfNew(archive)
                }
            }
        }
        return mValues
    }

    override fun updateSort(method: SortMethod, desc: Boolean, force: Boolean) {
        if (method != sortMethod || descending != desc || force) {
            sortMethod = method
            descending = desc
            mutableSortData.value = Pair(Pair(sortMethod, descending), Pair(sortFilter, onlyNew))
        }
    }
}
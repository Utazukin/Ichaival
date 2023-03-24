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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.utazukin.ichaival.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class ReaderTabViewModel : ViewModel() {
    val bookmarks = Pager(PagingConfig(5)) { DatabaseReader.database.archiveDao().getDataBookmarks() }.flow.cachedIn(viewModelScope)
}

abstract class SearchViewModelBase : ViewModel(), DatabaseDeleteListener {
    val totalSize
        get() = archivePagingSource.totalSize
    val searchResults
        get() = archivePagingSource.results
    protected abstract var archivePagingSource: ArchiveListPagingSourceBase
    protected val archiveDao by lazy { DatabaseReader.database.archiveDao() }
    private var collectJob: Job? = null
    private val archiveList = Pager(PagingConfig(ServerManager.pageSize), 0) { getPagingSource() }.flow.cachedIn(viewModelScope)

    init {
        DatabaseReader.registerDeleteListener(this)
    }

    private fun getPagingSource() : ArchiveListPagingSourceBase {
        archivePagingSource = archivePagingSource.copy()
        return archivePagingSource
    }
    suspend fun getRandom(excludeBookmarked: Boolean = true): Archive? = withContext(Dispatchers.IO) {
        var data: Collection<String> = archivePagingSource.results ?: archiveDao.getAllIds()

        if (excludeBookmarked)
            data = data.subtract(archiveDao.getBookmarks().map { it.id }.toSet())

        val randId = data.randomOrNull()
        if (randId != null) archiveDao.getArchive(randId) else null
    }

    abstract fun updateSort(method: SortMethod, desc: Boolean, force: Boolean = false)
    override fun onCleared() {
        super.onCleared()
        DatabaseReader.unregisterDeleteListener(this)
    }
    fun reset() = archivePagingSource.invalidate()

    fun monitor(scope: CoroutineScope, action: suspend (PagingData<Archive>) -> Unit) {
        collectJob?.cancel()
        collectJob = scope.launch { archiveList.collectLatest(action) }
    }

    fun cancel() {
        collectJob?.cancel()
        collectJob = null
    }

    override fun onDelete() {
        reset()
    }
}

class RandomViewModel : SearchViewModelBase() {
    override var archivePagingSource: ArchiveListPagingSourceBase = ArchiveListPagingSourceRandom()

    override fun updateSort(method: SortMethod, desc: Boolean, force: Boolean) {}
    fun filter(searchResult: ServerSearchResult) {
        with(archivePagingSource) {
            updateSearchResults(searchResult)
            invalidate()
        }
    }
}

class SearchViewModel : SearchViewModelBase() {
    override var archivePagingSource: ArchiveListPagingSourceBase = ArchiveListPagingSourceServer()
    private var initiated = false

    fun init(method: SortMethod, desc: Boolean, force: Boolean = false, isSearch: Boolean = false) {
        if (initiated && !force)
            return

        initiated = true
        with(archivePagingSource) {
            this.isSearch = isSearch
            updateSort(method, desc, true)
            invalidate()
        }
    }

    fun filter(searchResult: ServerSearchResult) {
        with(archivePagingSource) {
            updateSearchResults(searchResult)
            invalidate()
        }
    }

    override fun updateSort(method: SortMethod, desc: Boolean, force: Boolean) {
        with(archivePagingSource) {
            if (updateSort(method, desc, force))
                invalidate()
        }
    }
}

class StaticCategoryModel : ArchiveViewModel(), CategoryListener {
    private lateinit var results: List<String>
    private var categoryId: String? = null
    private var initiated = false

    init {
        CategoryManager.addUpdateListener(this)
    }

    fun filter(onlyNew: Boolean) {
        if (!onlyNew) {
            archivePagingSource.let {
                it.updateSearchResults(results)
                it.invalidate()
            }
        } else {
            viewModelScope.launch {
                archivePagingSource.let {
                    it.updateSearchResults(DatabaseReader.database.getNewArchiveIds(results))
                    it.invalidate()
                }
            }
        }
    }

    fun init(ids: List<String>, id: String, method: SortMethod, desc: Boolean, onlyNew: Boolean) {
        if (!initiated || id != categoryId) {
            initiated = true
            categoryId = id
            with(archivePagingSource) {
                isSearch = false
                updateSort(method, desc, true)
            }
            sortMethod = method
            descending = desc
            results = ids
            filter(onlyNew)
        }
    }

    override fun onCleared() {
        super.onCleared()
        CategoryManager.removeUpdateListener(this)
    }

    override fun onDelete() {
        categoryId = null
    }

    override fun onCategoriesUpdated(categories: List<ArchiveCategory>?) {
        categoryId = null
    }
}

open class ArchiveViewModel : SearchViewModelBase() {
    override var archivePagingSource: ArchiveListPagingSourceBase = ArchiveListPagingSourceLocal()
    protected var sortMethod = SortMethod.Alpha
    protected var descending = false
    private var job: Job? = null
    private var initiated = false

    fun init(method: SortMethod, desc: Boolean, filter: CharSequence, onlyNew: Boolean, isSearch: Boolean = false) {
        if (!initiated) {
            initiated = true
            with(archivePagingSource) {
                this.isSearch = isSearch
                updateSort(method, desc, true)
                invalidate()
            }
            sortMethod = method
            descending = desc
            filter(filter, onlyNew)
        }
    }

    fun filter(filter: CharSequence?, onlyNew: Boolean) {
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            val ids = internalFilter(filter ?: "", onlyNew)
            yield()
            with(archivePagingSource) {
                updateSearchResults(ids)
                invalidate()
            }
        }
    }

    private suspend fun internalFilter(filter: CharSequence?, onlyNew: Boolean) : List<String>? {
        if (filter == null)
            return emptyList()

        if (filter.isEmpty())
            return if (onlyNew) archiveDao.getNewArchives() else null

        val mValues = ArrayList<String>(DatabaseReader.MAX_WORKING_ARCHIVES)
        fun addIfNew(archive: Archive) {
            if (!onlyNew || archive.isNew)
                mValues.add(archive.id)
        }

        val totalCount = archiveDao.getArchiveCount()
        val terms = parseTermsInfo(filter)
        val titleSearch = filter.removeSurrounding("\"")
        for (i in 0 until totalCount step DatabaseReader.MAX_WORKING_ARCHIVES) {
            val allArchives = archiveDao.getArchives(i, DatabaseReader.MAX_WORKING_ARCHIVES)
            for (archive in allArchives) {
                if (archive.title.contains(titleSearch, ignoreCase = true))
                    addIfNew(archive)
                else {
                    var hasTag = true
                    for (t in terms) {
                        if (t.term.startsWith("-")) {
                            if (archive.containsTag(t.term.substring(1), t.exact)) {
                                hasTag = false
                                break
                            }
                        } else if (!archive.containsTag(t.term, t.exact)) {
                            hasTag = false
                            break
                        }
                    }

                    if (hasTag)
                        addIfNew(archive)
                }
            }
        }

        return mValues
    }

    override fun updateSort(method: SortMethod, desc: Boolean, force: Boolean) {
        with(archivePagingSource) {
            if (updateSort(method, desc, force))
                invalidate()
        }
    }
}
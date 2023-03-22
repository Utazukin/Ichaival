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

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import com.utazukin.ichaival.*
import kotlinx.coroutines.*

private fun <T : Any, TT : Any> DataSource.Factory<T, TT>.toLiveData(pageSize: Int = 50) = LivePagedListBuilder(this, pageSize).build()

class ReaderTabViewModel : ViewModel() {
    val bookmarks = Pager(PagingConfig(5)) { DatabaseReader.database.archiveDao().getDataBookmarks() }.flow.cachedIn(viewModelScope)
}

abstract class SearchViewModelBase : ViewModel(), DatabaseDeleteListener {
    var archiveList: LiveData<PagedList<Archive>>? = null
        protected set
    protected abstract val archiveDataFactory: ArchiveListDataFactoryBase
    protected val archiveDao by lazy { DatabaseReader.database.archiveDao() }

    init {
        DatabaseReader.registerDeleteListener(this)
    }

    suspend fun getRandom(excludeBookmarked: Boolean = true): Archive? = withContext(Dispatchers.IO) {
        var data: Collection<String> = archiveDataFactory.currentSource?.searchResults ?: archiveDao.getAllIds()

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
    fun reset() = archiveDataFactory.reset()
    override fun onDelete() {
        reset()
    }
}

class RandomViewModel : SearchViewModelBase() {
    override val archiveDataFactory = RandomArchiveListDataFactory()
    override fun updateSort(method: SortMethod, desc: Boolean, force: Boolean) {}
    fun filter(searchResult: ServerSearchResult) = archiveDataFactory.updateSearchResults(searchResult)

    init {
        archiveList = archiveDataFactory.toLiveData(ServerManager.pageSize)
    }
}

class SearchViewModel : SearchViewModelBase() {
    override val archiveDataFactory = ArchiveListDataFactory(false)

    fun init(method: SortMethod, desc: Boolean, force: Boolean = false, isSearch: Boolean = false) {
        if (archiveList != null && !force)
            return

        archiveDataFactory.isSearch = isSearch
        archiveDataFactory.updateSort(method, desc, true)
        archiveList = archiveDataFactory.toLiveData(ServerManager.pageSize)
    }

    fun filter(searchResult: ServerSearchResult) = archiveDataFactory.updateSearchResults(searchResult)

    override fun updateSort(method: SortMethod, desc: Boolean, force: Boolean) = archiveDataFactory.updateSort(method, desc, force)
}

class StaticCategoryModel : ArchiveViewModel(), CategoryListener {
    private lateinit var results: List<String>
    private var categoryId: String? = null

    init {
        CategoryManager.addUpdateListener(this)
    }

    fun filter(onlyNew: Boolean) {
        if (!onlyNew) {
            archiveDataFactory.updateSearchResults(results)
            updateSort(sortMethod, descending, true)
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                archiveDataFactory.updateSearchResults(DatabaseReader.database.getNewArchiveIds(results))
            }
        }
    }

    fun init(ids: List<String>, id: String, method: SortMethod, desc: Boolean, onlyNew: Boolean) {
        if (archiveList == null || id != categoryId) {
            categoryId = id
            archiveDataFactory.isSearch = false
            archiveDataFactory.updateSort(method, desc, true)
            archiveList = archiveDataFactory.toLiveData()
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
    override val archiveDataFactory = ArchiveListDataFactory(true)
    protected var sortMethod = SortMethod.Alpha
    protected var descending = false
    private var job: Job? = null

    fun init(method: SortMethod, desc: Boolean, filter: CharSequence, onlyNew: Boolean, isSearch: Boolean = false) {
        if (archiveList == null) {
            archiveDataFactory.isSearch = isSearch
            archiveDataFactory.updateSort(method, desc, true)
            archiveList = archiveDataFactory.toLiveData()
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
            archiveDataFactory.updateSearchResults(ids)
        }
    }

    private suspend fun getArchives() : List<Archive> {
        return withContext(Dispatchers.IO) {
            when (sortMethod) {
                SortMethod.Alpha -> if (descending) archiveDao.getTitleDescending() else archiveDao.getTitleAscending()
                SortMethod.Date -> if (descending) archiveDao.getDateDescending() else archiveDao.getDateAscending()
            }
        }
    }

    private suspend fun internalFilter(filter: CharSequence?, onlyNew: Boolean) : List<String>? {
        if (filter == null)
            return emptyList()

        val mValues = mutableListOf<String>()
        fun addIfNew(archive: Archive) {
            if (!onlyNew || archive.isNew)
                mValues.add(archive.id)
        }

        val allArchives = getArchives()
        if (filter.isEmpty())
            return if (onlyNew) allArchives.filter { it.isNew }.map { it.id } else null
        else {
            val terms = parseTermsInfo(filter)
            val titleSearch = filter.removeSurrounding("\"")
            for (archive in allArchives) {
                if (archive.title.contains(titleSearch, ignoreCase = true))
                    addIfNew(archive)
                else {
                    var hasTag = true
                    for (t in terms) {
                        if (t.term.startsWith("-"))  {
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

    override fun updateSort(method: SortMethod, desc: Boolean, force: Boolean) = archiveDataFactory.updateSort(method, desc, force)
}
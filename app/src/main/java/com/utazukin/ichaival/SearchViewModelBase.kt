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

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.paging.DataSource
import androidx.paging.LivePagedListBuilder
import androidx.paging.PagedList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

private fun <T, TT> DataSource.Factory<T, TT>.toLiveData(pageSize: Int = 50) = LivePagedListBuilder(this, pageSize).build()

class ReaderTabViewModel : ViewModel() {
    val bookmarks = DatabaseReader.database.archiveDao().getDataBookmarks().toLiveData(5)
}

abstract class SearchViewModelBase : ViewModel() {
    abstract val archiveList: LiveData<PagedList<Archive>>
    protected abstract val archiveDataFactory: ArchiveListDataFactory
    protected val archiveDao by lazy { DatabaseReader.database.archiveDao() }

    abstract suspend fun getRandom(excludeBookmarked: Boolean = true): Archive?
    abstract fun updateSort(method: SortMethod, desc: Boolean, force: Boolean = false)
}

class SearchViewModel : SearchViewModelBase() {
    override lateinit var archiveList: LiveData<PagedList<Archive>>
    override val archiveDataFactory = ArchiveListDataFactory(false)

    override suspend fun getRandom(excludeBookmarked: Boolean): Archive? {
        var data: Collection<String> = archiveDataFactory.currentSource?.searchResults ?: archiveDao.getAllIds()

        if (excludeBookmarked)
            data = data.subtract(archiveDao.getBookmarks().map { it.id })

        val randId = if (data.isNotEmpty()) data.random() else null
        return if (randId != null) archiveDao.getArchive(randId) else null
    }

    fun init(method: SortMethod, desc: Boolean, pageSize: Int, force: Boolean = false, isSearch: Boolean = false) {
        if (this::archiveList.isInitialized && !force)
            return

        archiveDataFactory.isSearch = isSearch
        archiveDataFactory.updateSort(method, desc, true)
        archiveList = archiveDataFactory.toLiveData(pageSize)
    }

    fun filter(searchResult: ServerSearchResult) = archiveDataFactory.updateSearchResults(searchResult)

    override fun updateSort(method: SortMethod, desc: Boolean, force: Boolean) = archiveDataFactory.updateSort(method, desc, force)
}

class ArchiveViewModel : SearchViewModelBase() {
    override lateinit var archiveList: LiveData<PagedList<Archive>>
    override val archiveDataFactory = ArchiveListDataFactory(true)

    override suspend fun getRandom(excludeBookmarked: Boolean) : Archive? {
        var data: Collection<String> = archiveDataFactory.currentSource?.searchResults ?: archiveDao.getAllIds()

        if (excludeBookmarked)
            data = data.subtract(archiveDao.getBookmarks().map { it.id })

        val randId = data.random()
        return archiveDao.getArchive(randId)
    }

    fun init(scope: CoroutineScope, method: SortMethod, desc: Boolean, filter: CharSequence, onlyNew: Boolean, isSearch: Boolean = false) {
        if (!this::archiveList.isInitialized) {
            archiveDataFactory.isSearch = isSearch
            archiveDataFactory.updateSort(method, desc, true)
            archiveList = archiveDataFactory.toLiveData()
            filter(filter, onlyNew, scope)
        }
    }

    fun filter(filter: CharSequence?, onlyNew: Boolean, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val ids = internalFilter(filter ?: "", onlyNew)
            archiveDataFactory.updateSearchResults(ids)
        }
    }

    private suspend fun internalFilter(filter: CharSequence?, onlyNew: Boolean) : List<String>? {
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
            val normalized = filter.toString().toLowerCase(Locale.ROOT)
            val spaceRegex by lazy { Regex("\\s") }
            for (archive in allArchives) {
                if (archive.title.toLowerCase(Locale.ROOT).contains(normalized) && !mValues.contains(archive.id))
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

    override fun updateSort(method: SortMethod, desc: Boolean, force: Boolean) = archiveDataFactory.updateSort(method, desc, force)
}
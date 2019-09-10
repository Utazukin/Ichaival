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
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject

@Dao
interface ArchiveDao {
    @Query("Select * from archive")
    fun getAll() : List<Archive>

    @Query("Select * from archive order by :sortField collate nocase desc")
    fun getAllDescending(sortField: String) : List<Archive>

    @Query("Select * from archive order by :sortField collate nocase asc")
    fun getAllAscending(sortField: String) : List<Archive>

    @Query("Select * from archive order by dateAdded desc")
    fun getDataDateDescending() : DataSource.Factory<Int, Archive>

    @Query("Select * from archive where id in (:ids) order by dateAdded desc")
    fun getDataDateDescending(ids: List<String>) : DataSource.Factory<Int, Archive>

    @Query("Select * from archive order by title collate nocase desc")
    fun getDataTitleDescending() : DataSource.Factory<Int, Archive>

    @Query("Select * from archive where id in (:ids) order by title collate nocase desc")
    fun getDataTitleDescending(ids: List<String>) : DataSource.Factory<Int, Archive>

    @Query("Select * from archive order by dateAdded asc")
    fun getDataDateAscending() : DataSource.Factory<Int, Archive>

    @Query("Select * from archive where id in (:ids) order by dateAdded asc")
    fun getDataDateAscending(ids: List<String>) : DataSource.Factory<Int, Archive>

    @Query("Select * from archive order by title collate nocase asc")
    fun getDataTitleAscending() : DataSource.Factory<Int, Archive>

    @Query("Select * from archive where id in (:ids) order by title collate nocase asc")
    fun getDataTitleAscending(ids: List<String>) : DataSource.Factory<Int, Archive>

    @Query("Select * from archive where id = :id limit 1")
    fun getArchive(id: String) : Archive?

    @Query("Select title from archive where id = :id limit 1")
    fun getArchiveTitle(id: String) : String?

    @Query("Update archive set isNew = :isNew where id = :id")
    fun updateNewFlag(id: String, isNew: Boolean)

    @Query("Select id from archive")
    fun getAllIds() : List<String>

    @Query("Select currentPage from archive where id = :id and currentPage >= 0 limit 1")
    fun getBookmarkedPage(id: String) : Int?

    @Query("Update archive set currentPage = :page where id = :id")
    fun updateBookmark(id: String, page: Int)

    @Query("Update archive set currentPage = -1 where id = :id")
    fun removeBookmark(id: String)

    @Query("Update archive set currentPage = -1 where id in (:ids)")
    fun removeAllBookmarks(ids: List<String>)

    @Query("Select * from readertab order by `index`")
    fun getBookmarks() : List<ReaderTab>

    @Query("Select * from readertab order by `index`")
    fun getDataBookmarks() : DataSource.Factory<Int, ReaderTab>

    @Query("Delete from archive where id = :id")
    fun removeArchive(id: String)

    @Query("Delete from archive where id in (:ids)")
    fun removeArchives(ids: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(vararg archives: Archive)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(archives: List<Archive>)

    @Delete
    fun removeArchive(archive: Archive)

    @Delete
    fun removeBookmark(tab: ReaderTab)

    @Delete
    fun clearBookmarks(tabs: List<ReaderTab>)

    @Update
    fun updateArchive(archive: Archive)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun updateBookmark(tab: ReaderTab)

    @Update
    fun updateBookmarks(tabs: List<ReaderTab>)

    @Query("Update archive set title = :title, tags = :tags, isNew = :isNew, dateAdded = :dateAdded where id = :id")
    fun updateFromJson(id: String, title: String, isNew: Boolean, dateAdded: Int, tags: Map<String, List<String>>)

    @Transaction
    fun updateFromJson(archives: Collection<ArchiveJson>) {
        for (archive in archives)
            updateFromJson(archive.id, archive.title, archive.isNew, archive.dateAdded, archive.tags)
    }
}

class DatabaseTypeConverters {
    @TypeConverter
    fun fromMap(value: Map<String, List<String>>) : String {
        val jsonObject = JSONObject()
        for (pair in value)
            jsonObject.put(pair.key, JSONArray(pair.value))
        return jsonObject.toString()
    }

    @TypeConverter
    fun fromString(json: String) : Map<String, List<String>> {
        val jsonObject = JSONObject(json)
        val map = mutableMapOf<String, List<String>>()
        for (key in jsonObject.keys()) {
            val tagsArray = jsonObject.getJSONArray(key)
            val tags = mutableListOf<String>()
            for (i in 0 until tagsArray.length())
                tags.add(tagsArray.getString(i))
            map[key] = tags
        }

        return map
    }
}

@Database(entities = [Archive::class, ReaderTab::class], version = 1, exportSchema = false)
@TypeConverters(DatabaseTypeConverters::class)
abstract class ArchiveDatabase : RoomDatabase() {
    abstract fun archiveDao(): ArchiveDao

    fun insertOrUpdate(archives: Map<String, ArchiveJson>) {
        val currentIds = archiveDao().getAllIds()
        val allIds = currentIds.union(archives.keys)
        archiveDao().updateFromJson(archives.values)
        val toAdd = archives.keys.minus(currentIds).map { Archive(archives.getValue(it)) }
        archiveDao().insertAll(toAdd)

        val toRemove = allIds.subtract(archives.keys).toList()
        archiveDao().removeArchives(toRemove)
    }

    @Transaction
    fun updateBookmark(tab: ReaderTab) {
        archiveDao().updateBookmark(tab)
        archiveDao().updateBookmark(tab.id, tab.page)
    }

    @Transaction
    fun removeBookmark(tab: ReaderTab, adjustedTabs: List<ReaderTab>) {
        archiveDao().removeBookmark(tab.id)
        archiveDao().removeBookmark(tab)
        archiveDao().updateBookmarks(adjustedTabs)
    }

    @Transaction
    fun clearBookmarks(tabs: List<ReaderTab>) {
        archiveDao().removeAllBookmarks(tabs.map { it.id } )
        archiveDao().clearBookmarks(tabs)
    }
}

private fun <T, TT> DataSource.Factory<T, TT>.toLiveData(pageSize: Int = 50) = LivePagedListBuilder(this, pageSize).build()

class ReaderTabViewModel : ViewModel() {
    val bookmarks = DatabaseReader.database.archiveDao().getDataBookmarks().toLiveData(5)
    val bookmarkMap = Transformations.map(bookmarks) {list -> list.associateBy { it.id } }
}

class ArchiveViewModel : ViewModel() {
    lateinit var archiveList: LiveData<PagedList<Archive>>
    private val mutableSortData = MutableLiveData<Pair<Pair<SortMethod, Boolean>, Pair<CharSequence, Boolean>>>()
    private var sortMethod: SortMethod = SortMethod.Alpha
    private var descending: Boolean = true
    private var sortFilter: CharSequence = ""
    private var onlyNew = false
    private lateinit var archiveDao: ArchiveDao

    fun getRandom() : Archive? {
        val data = internalFilter(sortFilter, onlyNew) ?: archiveDao.getAllIds()
        val randId = data.random()
        return archiveDao.getArchive(randId)
    }

    fun init(archiveDao: ArchiveDao, method: SortMethod = SortMethod.Alpha, desc: Boolean = false, filter: CharSequence = "", onlyNew: Boolean) {
        if (!this::archiveList.isInitialized) {
            this.archiveDao = archiveDao
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

    fun updateSort(method: SortMethod, desc: Boolean, force: Boolean = false) {
        if (method != sortMethod || descending != desc || force) {
            sortMethod = method
            descending = desc
            mutableSortData.value = Pair(Pair(sortMethod, descending), Pair(sortFilter, onlyNew))
        }
    }
}
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
    @Query("Select * from dataarchive")
    fun getAll() : List<DataArchive>

    @Query("Select * from dataarchive order by :sortField collate nocase desc")
    fun getAllDescending(sortField: String) : List<DataArchive>

    @Query("Select * from dataarchive order by :sortField collate nocase asc")
    fun getAllAscending(sortField: String) : List<DataArchive>

    @Query("Select * from dataarchive order by dateAdded desc")
    fun getDataDateDescending() : DataSource.Factory<Int, DataArchive>

    @Query("Select * from dataarchive where id in (:ids) order by dateAdded desc")
    fun getDataDateDescending(ids: List<String>) : DataSource.Factory<Int, DataArchive>

    @Query("Select * from dataarchive order by title collate nocase desc")
    fun getDataTitleDescending() : DataSource.Factory<Int, DataArchive>

    @Query("Select * from dataarchive where id in (:ids) order by title collate nocase desc")
    fun getDataTitleDescending(ids: List<String>) : DataSource.Factory<Int, DataArchive>

    @Query("Select * from dataarchive order by dateAdded asc")
    fun getDataDateAscending() : DataSource.Factory<Int, DataArchive>

    @Query("Select * from dataarchive where id in (:ids) order by dateAdded asc")
    fun getDataDateAscending(ids: List<String>) : DataSource.Factory<Int, DataArchive>

    @Query("Select * from dataarchive order by title collate nocase asc")
    fun getDataTitleAscending() : DataSource.Factory<Int, DataArchive>

    @Query("Select * from dataarchive where id in (:ids) order by title collate nocase asc")
    fun getDataTitleAscending(ids: List<String>) : DataSource.Factory<Int, DataArchive>

    @Query("Select * from dataarchive where id = :id limit 1")
    fun getArchive(id: String) : DataArchive?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(vararg archives: DataArchive)

    @Delete
    fun removeArchive(archive: DataArchive)

    @Update
    fun updateArchive(archive: DataArchive)
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

@Database(entities = [DataArchive::class], version = 1, exportSchema = false)
@TypeConverters(DatabaseTypeConverters::class)
abstract class ArchiveDatabase : RoomDatabase() {
    abstract fun archiveDao(): ArchiveDao

    fun getAll(method: SortMethod, descending: Boolean) : List<ArchiveBase> {
        val sortField = when (method) {
            SortMethod.Date -> "dateAdded"
            SortMethod.Alpha -> "title"
        }

        return if (descending) archiveDao().getAllDescending(sortField) else archiveDao().getAllAscending(sortField)
    }

    fun insertOrUpdate(archives: List<Archive>) {
        for (archive in archives) {
            val dataArchive = archiveDao().getArchive(archive.id)
            if (dataArchive == null)
                convertFromArchive(archive)
            else {
                val converted = DataArchive(archive.id, archive.title, archive.dateAdded, archive.isNew, archive.tags)
                archiveDao().updateArchive(converted)
            }
        }

        val toRemove = archiveDao().getAll().filter { !archives.any { a -> a.id == it.id } }
        for (archive in toRemove)
            archiveDao().removeArchive(archive)
    }

    private fun convertFromArchive(archive: Archive) {
        val converted = DataArchive(archive.id, archive.title, archive.dateAdded, archive.isNew, archive.tags)
        archiveDao().insertAll(converted)
    }
}

private fun <T, TT> DataSource.Factory<T, TT>.toLiveData() = LivePagedListBuilder(this, 50).build()

class ArchiveViewModel : ViewModel() {
    lateinit var archiveList: LiveData<PagedList<DataArchive>>
    private val mutableSortData = MutableLiveData<Pair<Pair<SortMethod, Boolean>, Pair<CharSequence, Boolean>>>()
    private var sortMethod: SortMethod = SortMethod.Alpha
    private var descending: Boolean = true
    private var sortFilter: CharSequence = ""
    private var onlyNew = false
    private lateinit var archiveDao: ArchiveDao

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
                                if (ids?.any() == true)
                                    archiveDao.getDataTitleDescending(ids).toLiveData()
                                else
                                    archiveDao.getDataTitleDescending().toLiveData()
                            } else {
                                if (ids?.any() == true)
                                    archiveDao.getDataTitleAscending(ids).toLiveData()
                                else
                                    archiveDao.getDataTitleAscending().toLiveData()
                            }
                        }
                        SortMethod.Date -> {
                            if (it.first.second) {
                                if (ids?.any() == true)
                                    archiveDao.getDataDateDescending(ids).toLiveData()
                                else
                                    archiveDao.getDataDateDescending().toLiveData()
                            } else {
                                if (ids?.any() == true)
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

        fun addIfNew(archive: ArchiveBase) {
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